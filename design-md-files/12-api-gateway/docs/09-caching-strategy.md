# 09 — Caching Strategy: API Gateway

---

## Objective

Define caching strategy at the API gateway layer — covering response caching, authentication caching, route configuration caching, and rate limit state — to minimize latency overhead and maximize gateway throughput.

---

## What the Gateway Caches

| Cache Type | Store | TTL | Purpose |
|---|---|---|---|
| JWT validation result | Redis | 5 min | Avoid crypto on every request |
| JWKS public keys | In-memory | 5 min | JWT signature verification |
| API key metadata | Redis | 5 min | Avoid DB lookup per request |
| Route configuration | In-memory | Refresh on change | Route lookup O(1) |
| Rate limit state | Redis | Window-based | Centralized enforcement |
| API response cache | Redis | Per endpoint TTL | Reduce backend load |
| Circuit breaker state | In-memory (per pod) | Sliding window | Fast failover decision |
| Service discovery | In-memory | 30s | Backend instance lookup |

---

## Layer 1: JWT Validation Cache

Most impactful cache — eliminates expensive crypto on repeated requests:

```
Request with JWT token:
  1. Hash token: SHA-256(token) → 64 chars (not stored, just lookup key)
  2. Check Redis: GET jwt_valid:{token_hash}
     → Hit: return cached claims (userId, roles, expiry) → 0.3ms
     → Miss: verify JWT signature + claims → store in Redis with TTL = min(token_expiry, 5min) → 5ms

Cache key: token_hash (not the token itself — never store tokens in cache)
TTL strategy: min(remaining token lifetime, 5 minutes)
  → If token expires in 2 minutes: TTL = 2 minutes
  → If token expires in 1 hour: TTL = 5 minutes (cache rotates frequently)
```

**Security note**: invalidate cache entry immediately on explicit logout. Gateway receives logout event → `DEL jwt_valid:{token_hash}` from revocation list.

**Cache hit rate**: typically 90-95% (users make multiple API calls per login session). At 100K RPS × 90% hit rate = 90K requests skip crypto = ~450 CPU seconds saved per second.

---

## Layer 2: JWKS Public Key Cache

JWT signature verification requires public key from auth service's JWKS endpoint:

```
GET /.well-known/jwks.json → Returns RSA public keys

Gateway: 
  - Fetch on startup
  - Cache in JVM memory (not Redis — no network hop)
  - Refresh every 5 minutes (background, non-blocking)
  - On JWT with unknown `kid`: force immediate JWKS refresh (key rotation scenario)
```

Without this cache: every JWT validation = HTTP call to auth service + RSA key parse.
With cache: local in-memory map lookup → nanoseconds.

---

## Layer 3: API Key Cache

API keys validated on every request:

```
Request: X-API-Key: sk_live_xyz

Gateway:
  1. Hash key: SHA-256(sk_live_xyz) → key_hash
  2. GET Redis: apikey:{key_hash} → { merchantId, scopes, rateLimit, active: true }
  3. If hit: use cached metadata → 0.3ms
  4. If miss: DB lookup → cache with TTL 5min → return

On API key revocation:
  → Auth service emits event → gateway's revocation consumer → DEL apikey:{key_hash}
  → Or: next 5-min TTL expiry (acceptable if immediate revocation not required)
```

---

## Layer 4: Route Configuration Cache

Route config is the gateway's core function — must be instant:

```
Route config (in-memory):
  Map<PathPattern, RouteConfig>
  
  Each RouteConfig:
    - upstream URL template
    - required auth level
    - rate limit config
    - circuit breaker config
    - cache TTL (if response cacheable)
    - timeout

Route lookup: O(1) hash map or trie-based (for path prefix matching)
Reload strategy: hot reload (no restart)
  → Config service emits config change event
  → Gateway downloads new config
  → Atomically swaps map (reference swap — zero downtime)
```

---

## Layer 5: API Response Cache

Gateway can cache backend responses — most impactful for throughput:

### Caching Rules

Only cache if:
- Method: GET (never POST/PUT/DELETE)
- No Authorization header (public endpoints)
- Response status: 200 (not 4xx/5xx)
- Response has `Cache-Control: public, max-age=N` header

### Cache Key

```
Cache key = MD5(method + path + queryString + relevant_headers)

Example:
  GET /api/products?category=electronics → key: md5("GET/api/products?category=electronics")
  
For auth endpoints (cache disabled):
  GET /api/orders → has Authorization header → no cache
```

### Per-Endpoint Cache TTL

Configured in route config:

| Endpoint | Cache TTL | Rationale |
|---|---|---|
| GET /api/products/{id} | 5 minutes | Product details rarely change |
| GET /api/categories | 10 minutes | Category list is static-ish |
| GET /api/search?q=... | 60 seconds | Search results change slowly |
| GET /api/public/config | 1 hour | App config |
| GET /api/orders/* | None | User-specific, requires auth |
| POST/PUT/DELETE | None | Mutations never cached |

### Cache Invalidation

Gateway can't easily know when backend data changes. Options:

1. **TTL-only**: simple; stale data for up to TTL duration
2. **Cache tags + purge**: backend sends purge request to gateway on data change
   - `POST /gateway-admin/cache/purge { tag: "product:123" }`
   - Gateway: DEL all keys tagged with `product:123`
3. **Short TTL + accept staleness**: most practical; 60s staleness acceptable for catalog

---

## Layer 6: Circuit Breaker State Cache

Circuit breaker state per upstream service:

```
In-memory per gateway pod (not centralized — different pods can have different circuit states):
  Map<serviceId, CircuitState>
  
  CircuitState:
    - CLOSED: all requests pass through
    - OPEN: all requests fail fast (no upstream call)
    - HALF_OPEN: one test request; close if success

Sliding window (last 10 requests per service):
  OPEN condition: 5+ failures in last 10 requests
  HALF_OPEN condition: after 30s in OPEN state
```

**Why not centralized?**: circuit state is a performance optimization, not a correctness requirement. Per-pod eventual consistency is acceptable. Centralizing adds latency.

---

## Cache Poisoning Prevention

Gateway caches are attack targets:

```
Vulnerabilities:
  - Request with manipulated headers → cached response served to other users
  - Response with attacker-controlled content → cached and served

Prevention:
  1. Normalize cache keys (lowercase headers, consistent param ordering)
  2. Never cache responses with Set-Cookie headers
  3. Validate Vary headers: if response varies by Accept-Language, include in cache key
  4. Never cache error responses (4xx, 5xx)
  5. Sanitize response before caching (remove internal headers)
  6. Separate cache namespace per API version
```

---

## Memory and Storage Sizing

### Redis Memory Estimate

| Cache Type | Key Count | Avg Size | Total |
|---|---|---|---|
| JWT validation | 1M active sessions | 200 bytes | 200 MB |
| API key metadata | 100K keys | 300 bytes | 30 MB |
| Rate limit state | 10M users × 5 keys | 50 bytes | 2.5 GB |
| API response cache | 100K unique URLs | 5 KB avg | 500 MB |
| **Total** | | | **~3.2 GB** |

Redis cluster: 3 masters × 4 GB = 12 GB available. Comfortable headroom.

### In-Memory (JVM) per Gateway Pod

| Cache Type | Size |
|---|---|
| Route config | ~50 MB |
| JWKS keys | < 1 MB |
| Circuit breaker state | < 10 MB |
| **Total per pod** | **~60 MB** |

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| JWT cache in Redis | 95% CPU reduction for auth | 5-min stale token possible after revocation |
| Response cache at gateway | Reduce backend load | Stale data; cache invalidation complexity |
| In-memory circuit breaker | No Redis dependency for fast fail | Per-pod state; inconsistent circuit behavior |
| JWKS in JVM memory | Nanosecond lookup | Key rotation needs JWKS refresh handling |
| API key cache 5-min TTL | Fast lookups | Revoked key may work for 5 min |

---

## Interview Discussion Points

- **"How does JWT caching affect security?"** → Trade-off: 5-min stale window. Mitigate: shorter TTL (1-2 min) for sensitive APIs (payments), revocation list for compromised tokens, logout triggers cache invalidation.
- **"Can you cache API responses at gateway level?"** → Yes, for public GET endpoints with Cache-Control headers. Never for auth-required endpoints (cache key doesn't include identity → different users see same response). Key design: include all varying factors in cache key.
- **"How do you prevent cache stampede when a hot cache key expires?"** → Probabilistic expiry (randomly re-compute slightly before expiry based on TTL and compute cost), or mutex lock (first request gets lock, others wait for it to finish and read updated cache).
- **"How do you keep rate limit state consistent across 10 gateway pods?"** → Centralized Redis. All pods read/write same Redis keys. Eventual consistency in rate limit is acceptable — if one Redis op is slightly delayed, user gets 1-2 extra requests. Not a security risk for non-payment APIs.
- **"What's the difference between gateway response cache and CDN cache?"** → CDN is at the edge (geographically distributed, 80% hit rate for static). Gateway cache is at origin (100% of requests reach gateway; ~50% hit rate). Both needed: CDN offloads geographically; gateway cache reduces backend load for cache misses reaching origin.
