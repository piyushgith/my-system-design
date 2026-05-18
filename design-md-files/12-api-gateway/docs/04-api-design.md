# 04 — API Design: API Gateway

## Objective

Define the Admin API (for managing routes, policies, and API keys), the Developer Portal API (for self-service key management), and the standards that govern the gateway's handling and transformation of proxied requests. This document covers API versioning, pagination, error handling, idempotency, and the contract between the gateway and its upstream services.

---

## Two Distinct API Surfaces

The gateway exposes two fundamentally different APIs:

1. **Admin API** — Used by internal teams to manage routes, policies, circuit breakers, and observability. High-privilege, low-traffic, internally accessible only. Think of it as a control plane API.
2. **Developer Portal API** — Used by external developers to manage their API keys, view usage, and browse API documentation. Lower privilege, internet-facing but authenticated.

The gateway itself does not expose a "gateway API" to end clients — it simply proxies their requests to upstream services. However, the gateway adds, transforms, and strips headers on those proxied requests, which constitutes an implicit contract.

---

## API Versioning Strategy

### URI Path Versioning (Chosen Approach)

All Admin and Developer Portal APIs are versioned with a path prefix:

```
/admin/v1/routes
/admin/v1/policies/rate-limits
/developer/v1/keys
```

**Rationale for path versioning over header versioning:**
- Path versioning is explicit and visible in logs, browser history, and load balancer routing rules.
- Header versioning (`Accept: application/vnd.gateway.v2+json`) is elegant but invisible in logs and difficult to test without custom clients.
- Query parameter versioning (`?version=2`) conflicts with proxied upstream parameters.

**Breaking vs. non-breaking changes:**
- Non-breaking (additive) changes: new optional response fields, new optional request query params, new endpoints → deploy without version bump.
- Breaking changes: removed fields, changed semantics, changed required fields → new major version (`/admin/v2/`).
- The v1 admin API is maintained for 12 months after v2 GA. After deprecation, clients are notified via email (developer portal) and response headers (`Sunset: <date>`).

### Gateway-Level API Versioning for Upstream Routes

For upstream service APIs proxied through the gateway, versioning is handled at the Route level:

- Route for `/api/v1/orders/*` → upstream `order-service-v1`
- Route for `/api/v2/orders/*` → upstream `order-service-v2`

The gateway does not perform URI rewriting between versions by default. Both versions of the upstream service must be simultaneously deployed during the transition period (Blue-Green or Canary). The gateway's traffic split configuration determines what percentage of traffic goes to each version.

---

## Admin API: Route Management

### Create Route

```
POST /admin/v1/routes
Content-Type: application/json
Authorization: Bearer <internal-admin-JWT>

Request Body:
{
  "name": "orders-service-route",
  "predicates": [
    { "type": "PATH", "pattern": "/api/v1/orders/**" },
    { "type": "METHOD", "values": ["GET", "POST", "PATCH"] }
  ],
  "filters": [
    { "name": "JwtAuthFilter", "args": { "requiredScopes": ["orders:read", "orders:write"] } },
    { "name": "RateLimitFilter", "args": { "policyId": "standard-tier-policy" } },
    { "name": "AddRequestHeader", "args": { "name": "X-Source", "value": "gateway" } },
    { "name": "CircuitBreakerFilter", "args": { "policyId": "order-service-cb-policy" } }
  ],
  "upstreamUri": "lb://order-service",
  "order": 100,
  "trafficSplit": [
    { "upstreamUri": "lb://order-service-v1", "weight": 90 },
    { "upstreamUri": "lb://order-service-v2", "weight": 10 }
  ],
  "tags": { "team": "orders", "criticality": "high" }
}

Response: 201 Created
{
  "routeId": "f7c3a1b2-...",
  "name": "orders-service-route",
  "status": "ACTIVE",
  "createdAt": "2026-05-18T10:00:00Z",
  "configVersion": "42"
}
```

### Update Route (Partial — PATCH semantics)

```
PATCH /admin/v1/routes/{routeId}
Content-Type: application/merge-patch+json

{
  "trafficSplit": [
    { "upstreamUri": "lb://order-service-v1", "weight": 0 },
    { "upstreamUri": "lb://order-service-v2", "weight": 100 }
  ]
}

Response: 200 OK — full route object returned
```

**Idempotency:** PATCH operations are idempotent. Applying the same patch twice produces the same result. The response includes an `ETag` header (based on the route's `configVersion`). Clients should use `If-Match` to prevent lost-update scenarios:

```
PATCH /admin/v1/routes/{routeId}
If-Match: "42"
```

If the route was modified between the client's GET and PATCH, the server returns `409 Conflict` with the current ETag.

### Delete Route

```
DELETE /admin/v1/routes/{routeId}

Response: 204 No Content
```

Deletion is soft — the route is marked `status: DELETED` and removed from the active route table, but the record is retained for 30 days for audit purposes. Hard deletion occurs after the retention window.

### List Routes

```
GET /admin/v1/routes?status=ACTIVE&tag.team=orders&page=0&size=25&sort=order,asc

Response: 200 OK
{
  "content": [ ... ],
  "page": {
    "size": 25,
    "number": 0,
    "totalElements": 143,
    "totalPages": 6
  },
  "_links": {
    "self": "/admin/v1/routes?page=0&size=25",
    "next": "/admin/v1/routes?page=1&size=25",
    "first": "/admin/v1/routes?page=0&size=25",
    "last": "/admin/v1/routes?page=5&size=25"
  }
}
```

**Pagination strategy:** Cursor-based pagination is preferred for large, frequently-changing datasets (avoids the "page drift" problem where records inserted between page fetches cause items to be skipped or duplicated). However, for the Admin API — which is low-traffic and manages at most tens of thousands of routes — offset pagination with a stable sort order is acceptable and easier to implement.

---

## Admin API: Rate Limit Policy Management

```
GET    /admin/v1/policies/rate-limits           — list policies
POST   /admin/v1/policies/rate-limits           — create policy
GET    /admin/v1/policies/rate-limits/{id}      — get policy
PATCH  /admin/v1/policies/rate-limits/{id}      — update policy
DELETE /admin/v1/policies/rate-limits/{id}      — delete (if no routes reference it)
```

**Referential integrity check on delete:** A rate limit policy cannot be deleted if any active route references it. The API returns `409 Conflict` with a list of routes that must be updated first.

---

## Developer Portal API: API Key Management

### Create API Key

```
POST /developer/v1/keys
Authorization: Bearer <developer-JWT>

{
  "name": "Production Integration Key",
  "scopes": ["orders:read", "products:read"],
  "expiresAt": "2027-05-18T00:00:00Z"
}

Response: 201 Created
{
  "keyId": "ak_abc123",
  "name": "Production Integration Key",
  "key": "gw_prod_a1b2c3d4e5f6...",   <-- plaintext shown ONCE, never retrievable again
  "keyPrefix": "gw_prod_a1",
  "scopes": ["orders:read", "products:read"],
  "expiresAt": "2027-05-18T00:00:00Z",
  "createdAt": "2026-05-18T10:00:00Z"
}
```

**Security note:** The plaintext key is returned once in the creation response and never stored server-side (only the SHA-256 hash is stored). The API response must not be cached by intermediary proxies (`Cache-Control: no-store` on this endpoint).

### Revoke API Key

```
DELETE /developer/v1/keys/{keyId}

Response: 204 No Content
```

Revocation is immediate: the key is marked revoked in the database AND an invalidation event is published to Kafka, which triggers Redis cache eviction across all Gateway Runtime instances. The gateway will reject the key within the cache propagation window (< 60 seconds, typically < 5 seconds via Kafka → Redis → Gateway reload).

### List API Keys

```
GET /developer/v1/keys

Response: 200 OK
{
  "keys": [
    {
      "keyId": "ak_abc123",
      "name": "Production Integration Key",
      "keyPrefix": "gw_prod_a1",       <-- prefix shown for identification
      "scopes": ["orders:read"],
      "status": "ACTIVE",
      "expiresAt": "2027-05-18T00:00:00Z",
      "lastUsedAt": "2026-05-17T23:59:12Z",
      "createdAt": "2026-05-18T10:00:00Z"
    }
  ]
}
```

Note: The actual key value is never returned after creation. Only the prefix (first 8 characters) is shown to help users identify which key is which.

---

## Error Handling Standards

All APIs return errors in a consistent format:

```json
{
  "type": "https://gateway.example.com/errors/rate-limit-exceeded",
  "title": "Rate Limit Exceeded",
  "status": 429,
  "detail": "You have exceeded 1000 requests per minute. Current count: 1001.",
  "instance": "/api/v1/orders",
  "extensions": {
    "retryAfter": 23,
    "limitDimension": "USER",
    "requestId": "f7c3a1b2-ab12-4cd3-8ef0-123456789abc",
    "traceId": "abc123def456"
  }
}
```

This format follows RFC 7807 (Problem Details for HTTP APIs). The `type` URI is a permanent identifier for the error class — it does not need to resolve to a web page.

### Standard Error Codes

| HTTP Status | Scenario | Retry? |
|---|---|---|
| 400 Bad Request | Malformed request body or invalid parameter | No — fix the request |
| 401 Unauthorized | Missing or invalid JWT/API key | No — authenticate first |
| 403 Forbidden | Valid credentials, insufficient permissions | No — check scopes |
| 404 Not Found | Route not found, resource not found | No |
| 409 Conflict | Concurrent update conflict (ETag mismatch) | Yes — with backoff, after re-fetching |
| 422 Unprocessable Entity | Semantically invalid request (e.g., weights don't sum to 100) | No — fix the request |
| 429 Too Many Requests | Rate limit exceeded | Yes — after Retry-After seconds |
| 502 Bad Gateway | Upstream returned an error | Yes — with exponential backoff |
| 503 Service Unavailable | Circuit breaker OPEN or gateway overloaded | Yes — after Retry-After |
| 504 Gateway Timeout | Upstream timed out | Yes — with backoff |

---

## Retry Strategy for Clients

The gateway communicates retry guidance explicitly in response headers:

```
HTTP/1.1 429 Too Many Requests
Retry-After: 23
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1716028800
X-Request-ID: f7c3a1b2-...
```

Clients should implement exponential backoff with jitter:
- Base delay: value from `Retry-After` header (if present) or 1 second
- Max delay: 60 seconds
- Jitter: ±20% of computed delay
- Max retries: 3 for 429; 5 for 502/503/504

Do not expose retry logic guidance in the gateway's proxy response to upstream services — upstreams define their own retry behavior.

---

## Idempotency for Admin Operations

For administrative write operations (creating routes, policies), the Admin API supports idempotency keys to prevent duplicate resource creation if a client retries after a network timeout:

```
POST /admin/v1/routes
Idempotency-Key: unique-client-generated-uuid

{ ... }
```

The server stores the idempotency key and its response for 24 hours. If the same key is presented within the window, the server returns the cached response without re-executing the operation. This prevents double-route creation from client retries.

---

## Request/Response Transformation Headers

### Headers Injected by Gateway (to upstreams)

| Header | Value | Purpose |
|---|---|---|
| `X-User-ID` | UUID from JWT sub claim | Upstream can use for user context without re-validating token |
| `X-Tenant-ID` | UUID from JWT tenant claim | Multi-tenancy context propagation |
| `X-Roles` | Comma-separated roles from JWT | Upstream can do fine-grained RBAC without token re-validation |
| `X-Request-ID` | UUID (generated or propagated) | Correlation across services |
| `X-Forwarded-For` | Original client IP | For upstream audit logging |
| `X-Gateway-Version` | Gateway build version | Debugging and compatibility checks |
| `traceparent` | W3C Trace Context | Distributed tracing propagation |

### Headers Stripped by Gateway (from client requests)

| Header | Reason for Stripping |
|---|---|
| `Authorization` | Prevents downstream services from re-validating or logging the JWT |
| `X-Admin-Override` | Prevents clients from injecting privileged headers |
| `X-Internal-*` | All internal headers must not be client-controlled |

### Headers Stripped from Upstream Responses (to clients)

| Header | Reason |
|---|---|
| `X-Powered-By` | Information leakage (reveals technology stack) |
| `Server` | Information leakage (reveals server version) |
| `X-Internal-Service-ID` | Internal routing information |

---

## API Documentation and Discovery

### OpenAPI (Swagger) for Admin and Developer APIs

Both the Admin API and Developer Portal API expose OpenAPI 3.0 specs at:
- `/admin/openapi.json`
- `/developer/openapi.json`

These specs are auto-generated from Spring Boot's `springdoc-openapi` integration. The Developer Portal UI uses these specs to generate interactive documentation.

### Upstream API Aggregation in Developer Portal

The Developer Portal aggregates OpenAPI specs from all upstream services (fetched via the gateway itself, or from a spec registry). It presents a unified API catalog to external developers, showing only the routes and operations accessible with their current scopes.

**Design decision:** The gateway does not serve the OpenAPI specs for upstream services directly. This avoids the gateway becoming a spec proxy (yet another responsibility). Instead, a dedicated spec aggregation job (run as a Kubernetes CronJob) fetches specs from upstream services and publishes them to the Developer Portal's database.

---

## Interview-Level Discussion Points

1. **URI path versioning at the gateway level vs. at the upstream service level:** If the upstream service is on v1 of its API and the gateway proxies `/api/v1/orders/*` to `order-service`, who manages the version bump when the order service publishes v2? The gateway route must be updated. Who owns that coordination, and how do you automate it?

2. **Idempotency key expiry — 24 hours is correct for most admin operations, but what about route creation that triggers immediate Kafka events?** If the first attempt creates the route and publishes a `RouteCreated` event but the client times out before receiving the 201, the client retries with the same idempotency key. The server returns 201 without re-creating the route. But the Kafka event was already published and route is already active. Is this correct behavior? How does the client know the difference?

3. **The `Retry-After` header — should the gateway ever calculate a dynamic retry time vs. a fixed one?** For a sliding window rate limiter, the exact time until the window slides is calculable. For a fixed window, it's the time until the next window. For a token bucket, it's the time until the next token is available. Does the added complexity of dynamic Retry-After values justify the precision?

4. **Header injection security:** The gateway injects `X-User-ID` from the JWT. If a developer accesses an upstream service directly (bypassing the gateway), they can inject arbitrary `X-User-ID` headers. How do upstream services protect against this? mTLS from the gateway? Network policy? Custom validation?

5. **OpenAPI spec aggregation from upstream services:** Upstream services must keep their OpenAPI specs accurate and up-to-date. What governance process ensures this? If an upstream changes an API without updating the spec, the gateway's route configuration and the Developer Portal documentation become stale. How do you detect this drift?
