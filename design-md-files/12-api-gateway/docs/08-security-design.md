# 08 — Security Design: API Gateway

---

## Objective

Define security architecture for an API Gateway — the first and most critical security enforcement layer in a microservices architecture, responsible for authentication, authorization, threat protection, and security policy enforcement across all APIs.

---

## API Gateway Security Role

The gateway is the security perimeter for all backend services:

```
Internet → [API Gateway — Security Enforcer]
                │
                ↓
        [Backend Services]
        (should TRUST gateway; minimal own auth logic)
```

**Gateway enforces**: authentication, authorization, rate limiting, IP filtering, WAF rules, SSL termination.
**Backend services trust**: validated JWT from gateway; no need to re-validate JWT in each service.

---

## Authentication

### JWT Authentication Flow

```
Client → POST /auth/login → Auth Service
  → Returns: { accessToken, refreshToken }

Client → GET /api/orders (with Bearer accessToken)
  → API Gateway:
      1. Extract JWT from Authorization header
      2. Verify signature (asymmetric: RS256, ECDSA P-256)
      3. Verify expiry, issuer, audience claims
      4. Extract userId, roles from claims
      5. Forward to backend with headers:
           X-User-Id: user123
           X-User-Roles: CUSTOMER,VIP
           X-Authenticated: true
```

**Algorithm**: RS256 (asymmetric RSA) preferred over HS256 (symmetric HMAC). Why: backend services only need public key to verify; private key stays with auth service only.

**JWKS endpoint**: public keys published at `/.well-known/jwks.json`. Gateway fetches and caches (5-minute TTL). No manual key distribution to each service.

### OAuth2 / OIDC Support

For third-party integrations:

```
Authorization Code Flow (web apps):
  1. User → Gateway → Redirect to IdP (Okta/Auth0/Keycloak)
  2. IdP authenticates → returns code
  3. Gateway exchanges code for access token
  4. Gateway validates token → forwards identity to backend

Client Credentials Flow (machine-to-machine):
  1. Service → POST /token with client_id + client_secret
  2. Gateway returns access token (short-lived: 1 hour)
  3. Service uses token for API calls
```

### API Key Authentication

For B2B/developer APIs:

```
Request: GET /api/products?api_key=pk_live_xyz
  OR
Request: GET /api/products with header X-API-Key: sk_live_xyz

Gateway:
  1. Extract API key
  2. Lookup key in Redis (cached from DB): GET apikey:{key_hash}
  3. If valid: extract merchantId, scopes, rate limits
  4. If invalid: return 401
  
API key storage:
  DB: bcrypt hash of key (never stored plaintext)
  Redis: { merchantId, scopes, rateLimit } (cached for 5 min)
```

### mTLS for Service-to-Service

Internal services calling gateway (east-west traffic):

- mTLS enforced: client certificate required from calling service
- Certificate issued by internal CA (Vault PKI)
- Gateway verifies: certificate is from trusted CA, service name matches expected caller
- No JWT needed for internal service calls

---

## Authorization

### Gateway-Level Authorization

Coarse-grained authorization at gateway:

| Rule | Action |
|---|---|
| `GET /api/public/*` | Allow all (no auth required) |
| `GET /api/orders/*` | Require authentication |
| `POST /api/orders/*` | Require `role: CUSTOMER` |
| `GET /api/admin/*` | Require `role: ADMIN` |
| `DELETE /api/admin/users/*` | Require `role: ADMIN` + `scope: user:delete` |
| `POST /api/payments/*` | Require auth + rate limit: 10/min |

**Coarse-grained only at gateway**: "is this user authenticated and do they have the right role?" Fine-grained ("can this user see this specific order?") belongs in the backend service.

### Authorization Policy Language

Using Kong/Spring Cloud Gateway: define in config (not code):

```yaml
routes:
  - id: orders-api
    predicates:
      - Path=/api/orders/**
    filters:
      - name: AuthFilter
        args:
          requiredRoles: CUSTOMER
      - name: RateLimitFilter
        args:
          limit: 100
          window: 60s
```

Policy as code → version controlled → auditable.

---

## Threat Protection

### WAF (Web Application Firewall)

Layer in front of gateway (AWS WAF / Cloudflare):

| Rule | Blocks |
|---|---|
| OWASP SQL injection | SQL in request params |
| OWASP XSS | Script tags in params |
| Size limits | Request body > 10MB |
| HTTP method filter | Unexpected methods (TRACE, OPTIONS on prod) |
| IP reputation | Known botnet, tor exit nodes |
| Geo-block | If required: block non-operating countries |
| Scanner detection | Security scanner user-agents |

### DDoS Protection

- Layer 3/4: AWS Shield / Cloudflare Magic Transit (volumetric DDoS)
- Layer 7: Rate limiting per IP (before auth)

```
Pre-auth rate limit (by IP):
  1,000 req/min per IP — prevents credential stuffing
  If IP exceeds: CAPTCHA challenge → then 429

Post-auth rate limit (by userId):
  See rate limit design above
```

### SQL Injection Prevention at Gateway

- Input validation: reject payloads with SQL keywords in GET params
- WAF SQL injection rules
- Beyond gateway: parameterized queries in each backend service (defense in depth)

### Request Size Limits

```
Request body max: 10MB (configurable per endpoint)
Header max size: 8KB
URL length max: 8KB
Multipart upload: handled by dedicated upload service (not through gateway)
```

---

## SSL/TLS

### TLS Termination at Gateway

```
Internet → [TLS termination at ALB] → [Gateway] → [Backend (HTTP, internal)]
```

- ALB handles TLS: offloads crypto from gateway CPU
- Gateway to backend: HTTP (internal private network — no TLS needed)
- If compliance requires internal encryption: mTLS from gateway to backend

**TLS configuration**:
```
Protocols: TLSv1.3, TLSv1.2 (disable 1.0, 1.1)
Ciphers: ECDHE-RSA-AES256-GCM-SHA384, ECDHE-RSA-AES128-GCM-SHA256
HSTS: Strict-Transport-Security: max-age=31536000; includeSubDomains
Certificate: ACM (auto-renew, no manual rotation)
```

---

## Security Headers

Gateway adds security headers to all responses:

```
Content-Security-Policy: default-src 'self'
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer-when-downgrade
Permissions-Policy: geolocation=(), camera=()
Cache-Control: no-store (for auth-required endpoints)
```

---

## Secrets Management for Gateway

Gateway holds sensitive config:

| Secret | How Stored |
|---|---|
| JWT public keys | JWKS endpoint (public — not secret) |
| JWT private key (if gateway issues tokens) | AWS Secrets Manager |
| Redis connection string | AWS Secrets Manager |
| Backend service URLs | Kubernetes ConfigMap (not secret) |
| API key hashing pepper | AWS Secrets Manager |
| TLS certificates | AWS ACM |

No secrets in config files or environment variables in plaintext. Gateway pod has IAM role → accesses Secrets Manager.

---

## Token Revocation

Standard JWT: cannot revoke before expiry. Gateway mitigation:

**Approach 1 — Short expiry + refresh**:
- Access token: 15-minute expiry
- Revoked token at most 15 minutes valid after revocation
- Acceptable for most use cases

**Approach 2 — Revocation list**:
- Redis Set: `revoked_tokens:{jti}` with TTL matching token expiry
- On revocation: add `jti` to Redis set
- Gateway: after JWT signature valid → check if `jti` in revocation set
- Adds 1ms Redis check per request; enables instant revocation

**Approach 3 — Opaque tokens**:
- Token is random string; validated by introspection API call to auth service
- Instant revocation
- Adds 50-100ms per request (auth service call)
- Acceptable only for low-RPS, high-security endpoints (admin)

**Recommendation**: short expiry (15 min) + optional revocation list for compromised tokens.

---

## Audit and Access Logging

Every request logged:

```json
{
  "timestamp": "2026-01-15T10:23:45Z",
  "requestId": "req-uuid",
  "clientIp": "203.x.x.x",
  "userId": "usr-123",
  "apiKeyId": null,
  "method": "POST",
  "path": "/api/orders",
  "statusCode": 201,
  "upstreamService": "order-service",
  "latencyMs": 145,
  "rateLimitRemaining": 87,
  "userAgent": "MyApp/2.1.0 (iOS 17)",
  "traceId": "trace-abc123"
}
```

**Access logs**: written to Kafka (async) → Elasticsearch for querying. Used for:
- Security incident investigation: who called what, when
- API usage analytics
- Rate limit debugging
- SLA reporting per endpoint

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| JWT cache (5 min) | 95% CPU reduction | Token revocation up to 5-min delayed |
| TLS termination at ALB | Offloads crypto | Internal traffic unencrypted (mitigate with private VPC) |
| API key in Redis cache | Fast lookup | 5-min stale window for revoked keys |
| Coarse-grained authz at gateway | Enforce consistently | Fine-grained rules must be in services |
| WAF before gateway | Block attacks before gateway CPU used | Additional latency (5-15ms); WAF cost |

---

## Interview Discussion Points

- **"How do you propagate identity to backend services?"** → JWT validated at gateway; extract userId, roles; add as HTTP headers `X-User-Id`, `X-User-Roles`; backend trusts these headers (only gateway can set them — internal network)
- **"How do you revoke a JWT immediately?"** → Short expiry (15 min) handles most cases. For immediate: Redis revocation list; check JTI on every request. Tradeoff: 1ms Redis check per request.
- **"How do you prevent services from being called directly, bypassing gateway?"** → Private subnet: backend services not accessible from internet, only from gateway (security group rules). mTLS: backend requires client certificate; only gateway has cert.
- **"Who owns the authorization logic — gateway or service?"** → Gateway: coarse-grained (authentication, role check). Service: fine-grained (can this user see THIS order?). Mixing them: gateway becomes a business logic dumping ground.
- **"How do you handle JWT expiry during long-running requests?"** → Access token expires during request: backend gets error from downstream service; returns 401 to client; client uses refresh token to get new access token; retries. For very long operations: use short-lived session tokens separate from JWT.
