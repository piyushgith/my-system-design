# 07 — Scaling Strategy: API Gateway

---

## Objective

Define scaling strategy for an API Gateway — the critical infrastructure that sits in front of all microservices and must handle the aggregate traffic of every API consumer with low latency overhead and high availability.

---

## API Gateway Scaling is Different

The API gateway is unique among services:

- **Every request passes through it**: 100% of API traffic → amplified impact of any bottleneck
- **It must be faster than the services behind it**: gateway overhead should be < 10ms
- **It is a hard single point of failure**: if gateway goes down, everything goes down
- **Stateless is non-negotiable**: any state in gateway = scaling complexity

---

## Scaling Tiers

| Tier | RPS | Architecture |
|---|---|---|
| Startup | 500 RPS | Single gateway instance (Kong/Spring Cloud Gateway) |
| Growth | 10,000 RPS | 3 gateway instances behind ALB |
| Scale | 100,000 RPS | Gateway cluster, Redis for rate limiting, CDN in front |
| Taking | 1M+ RPS | Multi-region, PoP-based, custom L4/L7 proxy |

---

## Gateway Performance Budget

Every millisecond added by gateway reduces user experience:

| Component | Target Latency | Max Allowed |
|---|---|---|
| JWT validation (cached) | < 1ms | 5ms |
| JWT validation (uncached) | < 5ms | 20ms |
| Rate limit check (Redis) | < 1ms | 5ms |
| Route lookup | < 0.1ms | 1ms |
| Request forwarding overhead | < 2ms | 10ms |
| **Total gateway overhead** | **< 10ms** | **30ms** |

Backend service latency (p99): 200ms. With gateway: 210ms. User perceives 210ms, not 200ms. Gateway must be negligible.

---

## Scaling Lever 1: Horizontal Scaling (Primary)

Gateway is stateless → horizontal scaling is the default:

```
Internet → CDN → ALB → [Gateway Pod 1] → Backend Services
                     → [Gateway Pod 2]
                     → [Gateway Pod 3]
                     → [Gateway Pod N]
```

- Add pods: Kubernetes HPA on CPU or RPS metric
- No session affinity needed (fully stateless)
- Target: 70% CPU utilization per pod before scale-out

**What makes the gateway stateless**:
- Route config: loaded from config service at startup; hot-reloaded on change (no restart)
- JWT validation: asymmetric keys (public key from JWKS endpoint, cached 5 minutes)
- Rate limiting: external Redis (not in-memory)
- Circuit breaker state: per-pod (acceptable; eventual consistency in circuit state)

---

## Scaling Lever 2: Rate Limiting via Redis

Per-user and per-IP rate limiting must be centralized (not per-pod):

```
Pod 1 handles request from user-X → check Redis rate limit
Pod 2 handles next request from user-X → check SAME Redis rate limit
→ Enforces correct rate across all pods
```

**Without centralized rate limiting**: each pod allows N req/min → total N × pod_count req/min → rate limiting ineffective.

**Redis Cluster** for rate limiting:
- 3-node cluster for HA
- Consistent hashing: same user → same Redis slot (locality)
- Throughput: Redis handles 100K ops/sec per node; easily handles 100K RPS gateway traffic
- TTL-based keys: sliding window with `ZADD` + `ZCOUNT` per user

---

## Scaling Lever 3: JWT Validation Cache

JWT validation is the most CPU-intensive gateway operation:

```
Option A (per request): 
  Verify JWT signature → crypto operation → 2-5ms CPU
  At 100K RPS: 100K signature verifications/sec → 50 CPU cores needed

Option B (cached):
  First request: verify signature → store { tokenHash → userId, claims, expiry } in Redis → TTL 5min
  Next request (same token): GET from Redis → 0.3ms → no crypto
  Cache hit rate: ~95% (users make multiple requests per token lifetime)
  Effective: 5K verifications/sec (5% miss rate) → 2.5 CPU cores needed
```

**Security consideration**: cached validation doesn't reflect immediate token revocation (max 5-min stale window). For banking: use shorter TTL or real-time revocation list check.

---

## Scaling Lever 4: CDN in Front of Gateway

For public APIs (catalog, search, public content):

- API responses for identical queries cached at CDN edge
- CDN cache key: URL + Accept-Language header
- TTL: 60s for search results, 5 minutes for catalog
- Cache hit: CDN returns response without hitting gateway at all

```
API Gateway origin sees:
  Without CDN: 100K RPS total
  With CDN (70% hit rate): 30K RPS to gateway
→ 70% reduction in gateway load for cacheable endpoints
```

**Not cacheable** via CDN: any request with Authorization header (user-specific response). CDN strips auth and caches only: public endpoints, GET requests without auth.

---

## Scaling Lever 5: Async Non-Critical Operations

Gateway performs several operations per request:

**Synchronous (blocking)**:
- JWT validation
- Rate limit check
- Route lookup
- Request forwarding

**Async (non-blocking)**:
- Access logging (buffer + flush to Kafka)
- Metrics collection (Prometheus scrape, not inline)
- Audit events (write to Kafka asynchronously)

If logging is synchronous: I/O blocks gateway thread. At 100K RPS, 1ms logging delay = 100 CPU-seconds/second wasted. Buffer logs in memory, flush every 100ms.

---

## Rate Limiting Architecture

```
Rate limiting strategies:
  1. Per IP: 1000 req/min — bot/DDoS protection
  2. Per user: 100 req/min — fair use
  3. Per API key: 10,000 req/min — partner APIs
  4. Per endpoint: /payments → 10 req/min per user (stricter)
  5. Per tenant (SaaS): 50,000 req/min per tenant

Implementation: Redis Token Bucket
  Key: rate:{strategy}:{identifier}
  ZADD to sorted set with timestamp as score
  ZREMRANGEBYSCORE to remove expired entries (sliding window)
  ZCARD to count current window requests
  If count > limit: return 429 with Retry-After header
```

---

## Load Balancing Between Gateway and Backend

Gateway performs L7 load balancing to backends:

```
Strategy: round-robin (default) — simple, works when all instances are equal
Strategy: least-connections — better when response times vary
Strategy: weighted — for canary (90% v1, 10% v2)
Strategy: consistent hash — for session-affinity requirements (rare)
```

Health checks: gateway polls backend `/health` every 5 seconds. Unhealthy instances removed from rotation immediately (not after timeout).

---

## Performance Bottlenecks

| Bottleneck | Symptom | Fix |
|---|---|---|
| JWT validation CPU | High gateway CPU, slow auth | JWT cache in Redis |
| Rate limit Redis latency | Gateway adds 5ms+ per request | Redis Cluster, locality-aware hashing |
| Route config reloading | Brief gateway pause on config change | Hot reload (no restart); blue-green config update |
| Upstream connection pool exhaustion | Gateway queuing requests | Increase connection pool; add backend instances |
| Access log I/O | Disk saturation | Async log buffering to Kafka |
| DDoS on single endpoint | Overwhelm specific backend | Per-endpoint rate limit; WAF IP blacklist |

---

## Multi-Region Gateway

At Taking scale: API gateway deployed at every region and PoP:

```
User in Mumbai → Nearest gateway PoP (Mumbai)
User in Tokyo  → Nearest gateway PoP (Tokyo)
User in London → Nearest gateway PoP (London)

→ Gateway handles: auth, rate limiting (local Redis)
→ Routes to: regional backend services
→ Global state (cross-region user rate limits): eventually consistent via replication
```

**Challenge**: cross-region rate limits. If user sends 100 req/min to Mumbai gateway and 100 req/min to Tokyo gateway → 200 req/min actual. Mitigate via: per-region limit (lower than global) or eventual sync of rate limit state.

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| JWT cache | 95% CPU reduction for auth | Up to 5-min stale window for revoked tokens |
| Centralized Redis rate limiting | Correct enforcement | Redis is additional dependency; latency per request |
| CDN in front | Massive read offload | Stale API responses; no auth-header caching |
| Async logging | Gateway not blocked by I/O | Log processing delay; potential loss on crash |
| Horizontal stateless scaling | Simple, unlimited scale | External Redis required for shared state |

---

## Interview Discussion Points

- **"How do you scale the gateway to 1M RPS?"** → Horizontal pods + CDN in front (70% offload) + JWT cache (reduce crypto CPU) + Redis rate limiting; at 1M RPS: add PoP-based routing (CDN at gateway layer)
- **"What's your gateway latency overhead budget?"** → < 10ms total. Break down: JWT cache hit (1ms) + rate limit Redis (1ms) + route lookup (0.1ms) + forwarding overhead (2ms) = ~4ms typical.
- **"How do you handle a Redis failure in the rate limiting layer?"** → Fail-open (allow requests) for most endpoints; fail-closed for payment/critical APIs. Rate limiting failure should not cause customer-facing failures for general APIs.
- **"What makes a good gateway vs bad gateway?"** → Good: transparent to services, fast, stateless, observable. Bad: business logic in gateway, stateful, slow. Gateway should do: auth, routing, rate limiting, observability. Should NOT do: business logic, data transformation, orchestration.
