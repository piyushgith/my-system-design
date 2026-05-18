# 14 — Interview Discussion Points: API Gateway

---

## Objective

Prepare for FAANG/senior backend interview discussions on API gateway design — covering the hard problems: traffic management, auth at scale, circuit breaking, rate limiting correctness, service mesh vs gateway, and when NOT to use a gateway.

---

## Expected Interviewer Opening Questions

- "Design an API Gateway for our microservices platform"
- "How would you add rate limiting to our API?"
- "How do you implement authentication centrally across all microservices?"
- "How would you implement circuit breaking?"
- "When should you use an API gateway vs a service mesh?"

**First question to ask**: "What's the scale? 100 RPS startup or 1M RPS global platform? And what protocols — REST only, or gRPC/WebSocket too?"

---

## Hardest Questions and Strong Answers

### Q: How do you implement rate limiting correctly at 100K RPS?

Shallow answer: "Use Redis to count requests per user."

**Strong answer**:
> There are three algorithms: fixed window (simple but burst-allows), sliding window (accurate but more Redis ops), token bucket (smooth rate, allows burst up to bucket size).

> For 100K RPS gateway: centralized Redis Cluster with sliding window. Key: `rate:{userId}`. ZADD current timestamp as score, ZREMRANGEBYSCORE to remove old entries, ZCARD to count. This costs 3 Redis ops per request = 300K ops/sec at 100K RPS. Redis handles 100K ops/sec per node, 3 nodes = 300K — at the limit.

> Optimization: use Redis pipeline to batch the 3 ops into 1 round trip. Or: token bucket with Lua script (atomic, 1 round trip). Or: in-memory token bucket per pod with approximate accuracy (5% over-limit tolerance, no Redis).

> Trade-off decision: exact rate limiting (Redis) vs approximate (in-memory) depends on whether this is a security control (needs exact) or fair use (approximate OK).

### Q: How do you implement circuit breaking at the gateway?

**Strong answer**:
> Circuit breaker per upstream service, using sliding window state machine. States: CLOSED (normal), OPEN (failing fast), HALF_OPEN (testing).

> Sliding window: track last N requests to service (e.g., last 10). If failure rate > 50% (5 of 10): open circuit. In OPEN state: return 503 immediately without calling upstream — protects upstream from thundering herd during recovery. After 30 seconds: HALF_OPEN — allow 1 test request. If success: CLOSED. If failure: OPEN again.

> Key implementation detail: circuit state is per gateway pod (not centralized). Different pods may have different circuit states. This is acceptable — eventual convergence. The cost of centralized circuit state (Redis round trip per request) is too high.

> At FAANG scale: service mesh (Envoy/Istio) handles circuit breaking at sidecar level — more accurate because it's at the instance level, not the gateway level.

### Q: How do you handle authentication for 50 microservices?

**Strong answer**:
> Centralized auth at gateway — the only scalable approach. Alternative: each service validates JWT independently. Problems: each service needs JWT library, key management, update coordination. 50 services × auth logic = 50 places to fix a security bug.

> Gateway pattern: validate JWT once at gateway → extract userId, roles → pass as trusted headers to backend (X-User-Id, X-User-Roles). Backend trusts these headers — they can only be set by the gateway (network policy enforces no external access to backend).

> For 100K RPS: JWT validation is the bottleneck (RSA signature verification is CPU-heavy). Solution: validate once, cache in Redis for 5 minutes. 95% cache hit rate. JWT validation goes from 5ms to 0.3ms for 95% of requests.

> The 5-minute stale window: for most APIs, acceptable. For payment or banking APIs: shorter TTL (1 min) + revocation list check.

### Q: When would you NOT use an API gateway?

**Strong answer**:
> Three scenarios where gateway adds more problems than it solves.

> First: low-traffic monolith. If you have 1 service and 100 users, adding a gateway is infrastructure overhead with no benefit. Just add auth middleware directly to the service.

> Second: high-performance data streaming. If services exchange gigabytes of binary data (ML model inference, video processing), routing through an HTTP gateway adds latency and bandwidth cost. Use direct service-to-service gRPC instead; auth via mTLS at service level.

> Third: too much logic in gateway. If the gateway accumulates business logic (data transformation, orchestration, aggregation beyond BFF pattern), it becomes a bottleneck. All services depend on it; can't deploy independently. This is the BFF anti-pattern at scale: one gateway that knows about every business domain.

### Q: API Gateway vs Service Mesh — when to use which?

**Strong answer**:
> Not "or" — they serve different purposes and are often used together.

> API Gateway: north-south traffic (external clients → services). Handles: auth, rate limiting, routing, API versioning, developer portal. Optimized for: external request handling, different auth mechanisms per route.

> Service Mesh (Istio/Envoy): east-west traffic (service → service). Handles: mTLS between services, service discovery, circuit breaking, retries, observability. Optimized for: secure, reliable inter-service communication.

> Together: gateway at the edge (external auth, routing), service mesh internally (mTLS, traffic management). This is the standard architecture at FAANG scale.

> When only gateway, no service mesh: startup/scale-up phase. Service mesh adds operational complexity (Istio has steep learning curve). Add service mesh when: microservices count > 10, security requires mTLS everywhere, traffic management per service instance needed.

---

## Tradeoff Discussions

### Build vs Buy API Gateway

| Option | Examples | When |
|---|---|---|
| Build (Spring Cloud Gateway) | Your own code | Full control, Java-native, simple routing |
| Open source (Kong) | Kong OSS | Plugin ecosystem, non-Java stack |
| SaaS (AWS API Gateway) | AWS | Fast time-to-market, pay-per-call |
| Service mesh + minimal gateway | Istio + Envoy | Kubernetes-native, complex traffic management |

**Spring Cloud Gateway** (Java/Spring Boot ecosystem):
- Native Java → same stack as backend services
- Simple routing, filters, predicates
- Good for: Java shops, need custom filter logic in Java
- Bad for: polyglot environments, high-performance (not as fast as Nginx/Envoy)

**Kong**:
- Nginx-based: very fast
- Rich plugin ecosystem: auth, rate limit, logging out of box
- Lua-based plugins (learning curve)
- Database-backed config (Postgres) or DB-less (declarative)
- Good for: polyglot, mature feature set, plugin needs

**AWS API Gateway**:
- No infrastructure to manage
- Tight AWS ecosystem integration (Lambda, Cognito, WAF)
- Limited customization
- Expensive at high volume ($3.50/million requests)
- Good for: AWS-native, serverless, fast start

### Monolithic Gateway vs Multiple Gateways (BFF Pattern)

| Approach | Benefit | Cost |
|---|---|---|
| Single gateway | Simple, one place to manage | Becomes bottleneck; knows all APIs |
| BFF (Backend for Frontend) | Tailored per client type | Multiple gateways to maintain |

**BFF pattern**:
```
Mobile BFF: optimized for mobile (compact responses, mobile auth flows)
Web BFF: full responses, session-based auth
Partner BFF: API key auth, rate limits, partner-specific data format
Internal BFF: mTLS, no rate limiting, full data
```

Useful when: mobile vs web have very different data needs. Avoid when: premature optimization adds complexity without benefit.

---

## Senior Engineer Discussion Points

### API Versioning Strategy

| Approach | Example | Tradeoff |
|---|---|---|
| URL versioning | `/api/v2/orders` | Simple, visible, clients must update URL |
| Header versioning | `API-Version: 2` | Cleaner URLs, harder to cache (vary by header) |
| Content-type | `Accept: application/vnd.myapp.v2+json` | REST pure, complex to implement |

**Recommendation**: URL versioning for public APIs (most cacheable, most visible, easiest to document). Header versioning for internal APIs.

**Sunset policy**: v1 sunset 12 months after v2 release. Sunset-Header response header added 6 months before. Deprecation notices in documentation.

### Rate Limiting Sophistication

Beyond simple "N req/min per user":

- **Burst allowance**: allow 2x limit for 10 seconds (token bucket burst)
- **Graduated pricing tiers**: free tier 100 req/min; paid tier 1000 req/min; enterprise unlimited
- **Endpoint-level limits**: /api/search 200/min but /api/payments 10/min for same user
- **IP-based limits for anonymous**: before auth, limit by IP
- **Tenant-level limits (multi-tenant)**: every request from Tenant A counts against Tenant A's limit; tenant isolation

### WebSocket and gRPC Proxying

REST gateway handling non-REST protocols:

**WebSockets**:
- Initial HTTP upgrade → gateway allows and proxies upgrade
- After upgrade: bidirectional stream; gateway is transparent proxy
- Challenge: gateway needs to maintain WebSocket connection state (not truly stateless)
- Solution: sticky sessions for WebSocket connections (exception to statelessness rule)

**gRPC**:
- Gateway must support HTTP/2 (gRPC is HTTP/2-based)
- Spring Cloud Gateway: supports gRPC proxying in HTTP/2 mode
- gRPC-Web: transcoding gateway (browser gRPC → gateway → real gRPC to backend)
- Challenge: gRPC streaming is long-lived; gateway timeout config matters

---

## Staff Engineer Discussion Points

### Gateway as Platform

Beyond routing: the gateway becomes a developer platform:

```
Developer Portal:
  - API documentation (auto-generated from OpenAPI specs)
  - API key management (self-serve)
  - Rate limit usage visualization
  - Webhook subscription management
  - SDK generation (from OpenAPI spec)

Monetization:
  - Tier-based rate limits (free/pro/enterprise)
  - Usage billing integration
  - Quota management
```

This shifts gateway from "infrastructure" to "product." Staff engineers think about: who are the gateway's customers? What makes it easy to use?

### Gateway Governance

At scale (50+ teams using the gateway):

```
Who can change routes?
  - Self-service: teams submit PR to gateway config repo
  - Review: platform team reviews for security/performance
  - Automated checks: lint, contract tests, no conflicting paths
  
Who can set rate limits?
  - Default per tier (platform team defines)
  - Exception process: team requests higher limit with justification
  
Who can add custom filters?
  - Plugins reviewed by security + platform team
  - Plugin sandbox: plugins can't access other routes' data
```

---

## Common Mistakes Interviewers Watch For

| Mistake | Why It's Wrong |
|---|---|
| Business logic in gateway | Gateway becomes deployment bottleneck for all teams |
| Per-pod rate limiting (no Redis) | Rate limits useless when users rotate across pods |
| Synchronous access logging | I/O blocks request handling at high RPS |
| Single gateway instance | SPOF — if it dies, everything dies |
| mTLS everywhere (including gateway→backend) | Extra latency; acceptable in CDE; overkill for most |
| Treating gateway as BFF without design | One gateway accumulating all transformations = big ball of mud |
| JWT validation per microservice | 50 places to fix security bug; key distribution nightmare |
| Ignoring gRPC/WebSocket requirements | Design starts with REST; adding gRPC later is painful |

---

## Numbers to Know

| Metric | Value |
|---|---|
| Spring Cloud Gateway throughput | ~50K RPS per pod (JVM, low filter count) |
| Kong throughput | ~100K+ RPS per node (Nginx-based) |
| Envoy throughput | ~1M+ RPS per instance (C++, used by FAANG) |
| Redis ZADD latency | 0.1ms (local network) |
| RSA signature verification (2048-bit) | 0.5-2ms CPU |
| JWT cache hit rate (typical) | 90-95% |
| Circuit breaker check overhead | < 0.1ms (in-memory) |
| P99 gateway overhead target | < 30ms |

---

## Interview Tips (API Gateway-Specific)

1. **Establish scale early** — 1K vs 1M RPS changes whether you use Spring Cloud Gateway vs Kong vs Envoy
2. **Distinguish north-south vs east-west** — gateway for external; service mesh for internal; both for FAANG
3. **Know the 3 rate limit algorithms** — fixed window, sliding window, token bucket; know when each fits
4. **Circuit breaker states** — CLOSED/OPEN/HALF_OPEN; know transition conditions; know why per-pod is acceptable
5. **Auth header propagation** — validate once at gateway, propagate as trusted headers; no re-validation in services
6. **"Don't put business logic in gateway"** — say this unprompted; shows architectural awareness
7. **BFF pattern** — know when it helps (different clients different needs) vs when it's overengineering
8. **Failure mode**: "what if Redis dies?" → fail-open for rate limiting (except payments); JWT falls to crypto; gateway stays up
