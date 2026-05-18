# 00 — Requirements Analysis: API Gateway

## Objective

Define the complete functional and non-functional requirements for a production-grade API Gateway capable of serving as the single entry point for all client traffic to a microservices backend. This document anchors every subsequent architectural decision and ensures design tradeoffs are evaluated against real-world constraints.

---

## Problem Statement

As organizations decompose monoliths into microservices, clients — mobile apps, single-page applications, third-party integrators — face the complexity of calling dozens of services with inconsistent APIs, varying authentication mechanisms, and unpredictable reliability. An API Gateway centralizes cross-cutting concerns: routing, authentication, rate limiting, observability, and traffic management. The question is not whether to build one, but how to scope it correctly.

---

## Functional Requirements

### Core Traffic Management
- Route incoming HTTP/HTTPS requests to the correct upstream microservice based on path, host, headers, or query parameters.
- Support path rewriting, stripping prefixes, and redirects.
- Load balance across multiple instances of each upstream service.
- Support multiple protocols: REST (HTTP/1.1, HTTP/2), WebSocket, gRPC, Server-Sent Events (SSE).
- Enable traffic splitting for canary releases and A/B testing (e.g., 5% to v2, 95% to v1).

### Authentication and Authorization
- Validate JWT tokens (signature, expiry, issuer, audience) without calling an upstream auth service on every request.
- Support OAuth2 token introspection for opaque tokens.
- Enforce RBAC policies at the gateway level: certain routes require specific roles or scopes.
- Support API key authentication for machine-to-machine integrations.
- Allow anonymous access for public endpoints with configurable allowlists.

### Rate Limiting and Throttling
- Enforce rate limits per: user, API key, IP address, service endpoint, tenant (multi-tenancy).
- Support multiple rate limit strategies: fixed window, sliding window, token bucket, leaky bucket.
- Return standard 429 Too Many Requests responses with Retry-After headers.
- Apply global rate limits and per-route rate limits independently.

### Request/Response Transformation
- Add, remove, or modify request headers (e.g., inject X-User-ID from JWT claims).
- Strip internal headers before forwarding to clients.
- Support request body transformation for protocol bridging (REST-to-gRPC transcoding).
- Response aggregation: fan out to multiple services and merge results (BFF pattern).

### API Aggregation (BFF Pattern)
- Support backend-for-frontend aggregation: single client request triggers parallel upstream calls, merged into one response.
- Allow mobile, web, and partner BFF layers to be configured independently.

### Developer Portal and API Key Management
- Issue and revoke API keys for third-party developers.
- Associate API keys with rate limit tiers, allowed endpoints, and expiry dates.
- Provide a self-service developer portal for API key management.
- Support API versioning: route v1 and v2 clients to different upstream versions.

### Service Discovery Integration
- Integrate with service registries (Kubernetes service discovery, Consul, Eureka) to dynamically resolve upstream addresses.
- Health-check upstreams and remove unhealthy instances from the routing pool.

### Circuit Breaking and Resilience
- Detect upstream failures and open circuit breakers to prevent cascade failures.
- Support fallback responses for degraded-mode operation.
- Implement retry logic with exponential backoff and jitter.
- Apply timeout enforcement per route.

---

## Non-Functional Requirements

| Dimension | Requirement |
|---|---|
| Latency overhead | < 5ms p99 added latency for a passthrough request (no heavy transformation) |
| Availability | 99.99% (four nines) — the gateway is on the critical path for all traffic |
| Throughput | Handle 500K RPS at peak; design for 1M RPS with horizontal scaling |
| Scalability | Horizontal scale-out without downtime; stateless instances |
| Durability | Rate limit state and API key data must survive gateway restarts |
| Security | TLS termination, mutual TLS to upstreams, OWASP compliance |
| Observability | Per-request traces, structured access logs, real-time metrics dashboards |
| Compliance | Audit logs for all admin actions (key creation, route changes) |
| Multi-tenancy | Tenant isolation at the rate limit and routing level |
| Portability | Deployable on-premises, AWS, GCP; no vendor lock-in for core routing |

---

## Assumptions

- The gateway sits at the edge (north-south traffic). East-west service-to-service traffic is handled by a service mesh (Istio/Envoy sidecar).
- Upstream services expose well-defined REST or gRPC contracts.
- A dedicated Identity Provider (IdP) — Keycloak or Auth0 — issues JWTs. The gateway validates tokens using cached JWKS public keys; it does not issue tokens.
- API key metadata and rate limit counters are stored in Redis. Redis is treated as a critical dependency.
- The gateway is stateless by design. All shared state (rate counters, route configs, API keys) lives in Redis or a database.
- For the purposes of this design, Spring Cloud Gateway (reactive, WebFlux-based) is the implementation runtime. Comparisons to Kong, Nginx, and AWS API Gateway are discussed in the architecture file.

---

## Constraints

- **Spring Boot ecosystem**: Teams are Java-native. A custom Java-based gateway avoids introducing a new operational paradigm (Kong requires Lua knowledge, Nginx requires C module expertise).
- **No vendor lock-in for routing logic**: Core route definitions must be exportable and not tied to a specific cloud provider.
- **Redis as rate limit store**: Must function in Redis Cluster mode for HA. If Redis is unavailable, the gateway must fail open (allow traffic) or fail closed (block traffic) based on a configurable policy.
- **Zero-downtime config updates**: Route configuration changes must take effect without restarting gateway instances.

---

## Scale Estimation

### Traffic Assumptions

| Metric | Value |
|---|---|
| Peak RPS at gateway | 500,000 |
| Sustained average RPS | 150,000 |
| P99 request duration (upstream) | 200ms |
| P99 request duration (gateway overhead) | < 5ms |
| Average request payload | 2KB |
| Average response payload | 8KB |

### Capacity Planning

**Bandwidth:**
- Ingress: 500K RPS × 2KB = 1 GB/s inbound
- Egress: 500K RPS × 8KB = 4 GB/s outbound
- Total: ~5 GB/s peak; requires 10GbE NICs minimum per node

**Gateway Instance Sizing:**
- A single Spring Cloud Gateway instance on 4 vCPU / 8GB RAM handles ~20K–30K RPS in reactive (non-blocking) mode
- At 500K RPS: minimum 20 instances needed; recommend 30 for headroom
- Kubernetes Horizontal Pod Autoscaler (HPA) scales on CPU and custom RPS metric

**Redis for Rate Limiting:**
- Each rate limit check: 1–2 Redis operations (INCR + EXPIRE or a Lua script)
- 500K RPS × 2 Redis ops = 1M Redis ops/sec
- Redis Cluster with 6 shards (3 primary, 3 replica) handles ~500K ops/sec per shard → 3M ops/sec cluster capacity
- Sizing: conservative 6-node cluster; each node 4 vCPU, 32GB RAM

**Rate Limit Counter Storage:**
- 1M unique users × 100 bytes per counter × 10 time windows = ~1GB (trivial for Redis)
- API key store: 1M API keys × 500 bytes = 500MB

**Config Store (Route Definitions):**
- ~10,000 routes × 1KB = 10MB — trivially small; can be stored in Redis, DB, or config server

---

## Read/Write Patterns

| Operation | Pattern | Frequency |
|---|---|---|
| Request routing decision | Read (route table) | 500K/sec |
| JWT validation (JWKS cache) | Read (in-memory + Redis) | 500K/sec |
| Rate limit check | Read + Write (Redis INCR) | 500K–1M/sec |
| API key lookup | Read (Redis cache) | 500K/sec |
| Route config reload | Read (polling or push) | ~1/sec |
| Admin: create/revoke API key | Write (DB + Redis invalidation) | Rare |
| Admin: update route config | Write (DB + Redis/event publish) | Rare |
| Access log write | Write (async, to Kafka or file) | 500K/sec |

**Key insight:** The gateway is overwhelmingly read-dominated at runtime. Write operations are either rare (admin) or fire-and-forget (logging). This justifies aggressive in-memory caching and async log offloading.

---

## Latency Expectations

| Operation | Target p50 | Target p99 |
|---|---|---|
| Gateway passthrough (no transformation) | < 1ms overhead | < 5ms overhead |
| JWT validation (cached JWKS) | < 0.5ms | < 2ms |
| Rate limit check (Redis) | < 1ms | < 3ms |
| Route config lookup (in-memory) | < 0.1ms | < 0.5ms |
| BFF aggregation (2 upstream calls) | Upstream p99 × 1.2 | Upstream p99 × 1.5 |

**Critical constraint:** The gateway must not become a latency bottleneck. Every added millisecond multiplies across millions of requests. Synchronous, blocking operations are unacceptable in the hot path.

---

## Availability Targets

| Component | Target SLA |
|---|---|
| API Gateway cluster | 99.99% (< 52 min downtime/year) |
| Rate limiting (Redis) | 99.95% (degraded mode acceptable) |
| Auth validation (JWKS cache) | 99.99% (cache insulates from IdP outages) |
| Admin API (route management) | 99.9% (non-critical path) |
| Developer portal | 99.5% (best-effort) |

**Design implication:** The gateway must be designed to function in degraded mode when Redis or the IdP is unavailable. Failing closed (blocking all traffic) is catastrophic. Failing open (serving traffic without rate limiting) is acceptable for short outages.

---

## Interview-Level Discussion Points

1. **Why validate JWT at the gateway vs. at each service?** Centralizing reduces duplication, but it creates a single point of trust. What happens if the gateway is compromised? How does token revocation propagate?

2. **Rate limiting accuracy vs. performance:** Distributed rate limiting across 30 gateway instances requires a shared counter in Redis. This introduces network latency. Local in-memory counters are faster but allow bursting beyond limits when instances are imbalanced. What strategy do you choose and why?

3. **The JWKS caching problem:** Caching public keys improves performance but delays key rotation propagation. How long should JWKS be cached? What's the attack window if a compromised key is rotated out?

4. **Config hot-reload without restart:** How do you push route config changes to 30 stateless gateway instances simultaneously without causing a thundering herd? Push (Kafka/Redis pub-sub) vs. pull (polling) — tradeoffs?

5. **Back-of-envelope sanity check:** 500K RPS × 5ms gateway overhead = 2,500 CPU-seconds per second. At 30 instances with 4 vCPUs each = 120 CPU cores. Each core provides 1 CPU-second per second. 2,500 / 120 ≈ 20 cores dedicated to gateway overhead alone. Is this realistic? Where are the actual CPU bottlenecks?
