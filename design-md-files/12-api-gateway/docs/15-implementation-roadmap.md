# 15 — Implementation Roadmap: API Gateway

---

## Objective

Define phased implementation from a basic reverse proxy to a production-grade API gateway platform — with feature scope, architecture evolution, and team scaling at each phase.

---

## Phase 0 — MVP: Basic Reverse Proxy (Weeks 1-3)

### Goal

Route external requests to backend services. Minimum viable security. No complexity.

### Features

- URL-based routing: `/api/orders/*` → order-service
- JWT validation (basic: verify signature, check expiry)
- HTTPS termination (at ALB)
- Access logging (simple: request + response + latency)
- Health check endpoint: `GET /health`

### Architecture

- Spring Cloud Gateway (single instance for MVP)
- Route config in `application.yml` (no external config service yet)
- JWT validation via `spring-security-oauth2-resource-server`
- Logging to stdout (collected by K8s)
- No Redis, no Kafka

### Not Yet

- Rate limiting
- Circuit breakers
- Config hot-reload
- API versioning

### Team

- 1 backend engineer (1-2 weeks to get basic gateway working)

### Success Criteria

- All backend services reachable via gateway, HTTPS works, JWT rejected correctly

---

## Phase 1 — V1: Production Basics (Weeks 4-12)

### Goal

Gateway is production-ready for real users. HA, rate limiting, auth, observability.

### Features Added

- Rate limiting (Redis token bucket, per user + per IP)
- Circuit breakers (Resilience4j, per upstream service)
- JWT validation cache (Redis, 5-min TTL)
- API key authentication (for partner/developer APIs)
- Request/response header manipulation
- CORS configuration
- Security headers (CSP, HSTS, X-Frame-Options)
- WAF rules (basic OWASP set)
- Structured access logging (JSON to Kafka)
- Prometheus metrics export (RED method per route)
- Distributed tracing (OpenTelemetry, Jaeger)

### Architecture

- 3 gateway pods (one per AZ) behind ALB
- Redis Cluster (3 nodes): rate limiting + JWT cache
- Kafka: access log publisher (async, ring buffer)
- Kubernetes HPA: scale on CPU
- Config: YAML in ConfigMap (not hot-reloadable yet — restart required)
- PodDisruptionBudget: min 2 pods always available

### API Versioning

- URL versioning introduced: `/api/v1/*`
- Gateway routes v1 → backend v1 endpoints
- Backward compatible from day 1

### Team

- 2 backend engineers (gateway + Redis integration)
- 1 SRE (Kubernetes config, monitoring)

### Success Criteria

- 99.99% availability over 30 days, rate limiting blocks abuse, auth correct, observability in place

---

## Phase 2 — V2: Developer Platform (Months 3-9)

### Goal

Gateway becomes a self-service platform for internal teams and external developers.

### Features Added

- Config hot-reload (Kafka-based, no restart for route changes)
- API key self-service portal (developers create + manage keys)
- Usage analytics per API key (Kafka → ClickHouse)
- API documentation (auto-generated from OpenAPI specs)
- Request validation (schema validation for request bodies)
- Response transformation (field filtering, pagination normalization)
- WebSocket proxying (for real-time APIs)
- gRPC-Web transcoding (browser clients → gRPC backend)
- Canary traffic splitting (per-route weighted routing)
- Custom error response formatting (per API standard)
- Developer sandbox environment (separate gateway instance, mock backends)

### Architecture

- 6+ gateway pods (HPA: 6-50 based on traffic)
- Config Service: separate microservice manages route config
- Kafka: config changes → gateway hot-reload
- API Key Service: separate microservice (create, revoke, scope keys)
- CDN (CloudFront) in front of gateway for public endpoints
- Multiple gateway "flavors": public-gateway + internal-gateway (separate deployments)

### Team

- 3 backend engineers (gateway features)
- 1 frontend engineer (developer portal)
- 1 SRE
- 1 developer relations (documentation, developer experience)

### Risks

- Config hot-reload: edge case where partially applied config causes routing issues
- WebSocket sticky sessions: complicates stateless gateway (sticky sessions needed per connection)
- gRPC transcoding: protocol mismatch debugging is hard

### Success Criteria

- 10K+ API keys in use, config changes in < 5s without restart, WebSocket and gRPC routes live

---

## Phase 3 — V3: Global Scale (Months 10-18)

### Goal

Low-latency global API platform. Enterprise-grade governance. Advanced traffic management.

### Features Added

- Multi-region deployment (3 regions: Mumbai, Singapore, Ireland)
- Route 53 latency-based routing across regions
- BFF (Backend for Frontend): separate gateway instances per client type (mobile, web, partner)
- Advanced canary: 5% → 25% → 100% with automated metrics-based promotion
- Feature flags integration (LaunchDarkly/Unleash at gateway level)
- A/B testing support (route % of traffic to A vs B backend)
- API monetization: tier-based rate limits (free/pro/enterprise)
- Developer marketplace: publish and discover APIs
- GraphQL gateway (federated schema from multiple services)
- Synthetic monitoring (every 60s end-to-end test)
- Automated SLO dashboards with error budget tracking

### Architecture

- Multi-region Kubernetes (EKS in 3 regions)
- Global CDN (Cloudflare) in front of all regions
- Per-region Redis Cluster (rate limiting)
- Cross-region rate limit sync: eventual consistency (Redis replication, lag acceptable)
- Service mesh (Istio) internally (mTLS between all services)
- Gateway responsible for north-south only; Istio for east-west

### Team

- 5 gateway engineers
- 2 SRE (global operations)
- 1 developer experience engineer
- 1 security engineer (WAF rules, auth policy)

---

## Phase 4 — Taking Scale (Year 2+)

### Goal

Custom high-performance gateway for extreme scale.

### Features

- Custom L4/L7 proxy (Envoy-based or custom C++ — if Spring Gateway too slow)
- PoP (Point of Presence) deployment: gateway at CDN edge nodes
- Service mesh: Istio fully deployed, gateway uses Envoy sidecar
- Real-time traffic analysis and automated WAF rule updates
- ML-based anomaly detection at gateway (bot detection, attack pattern recognition)
- Custom Envoy filters in WASM (WebAssembly) for performance

### Architecture

```
User → Cloudflare PoP (edge Envoy) → Regional EKS (Envoy-based gateway) → Services
```

At this scale: Spring Cloud Gateway is too slow. Envoy (C++, same used by Google/Netflix): 5-10x higher throughput per pod.

---

## Architecture Evolution Summary

```
Phase 0: Single SCG pod → basic routing
Phase 1: HA + Redis + Kafka + circuit breakers
Phase 2: Config hot-reload + developer portal + multi-protocol
Phase 3: Multi-region + BFF + service mesh
Phase 4: Custom Envoy + PoP + ML-based WAF
```

---

## When to Switch from Spring Cloud Gateway

| Scale | Decision |
|---|---|
| < 50K RPS | Spring Cloud Gateway (simple, Java-native) |
| 50K-500K RPS | Kong (Nginx-based, 2-5x faster) OR larger SCG pods |
| 500K-5M RPS | Envoy + Istio (purpose-built, C++) |
| 5M+ RPS | Custom proxy or Cloudflare Workers at edge |

**Rule**: migrate gateway technology when throughput capacity is the bottleneck. Migration is expensive; don't do it prematurely.

---

## Interview Discussion Points

- **"What's your MVP gateway scope?"** → Routing + JWT validation + HTTPS. That's it. Add rate limiting in Phase 1 when users exist to rate-limit. Gateway complexity grows with scale, not ahead of it.
- **"When do you add Redis to the gateway?"** → Phase 1, when you have multiple pods. Single pod: in-memory rate limit works. Multiple pods: must centralize state or rate limits are ineffective.
- **"When do you add a service mesh?"** → Phase 3: when you have 10+ services and need mTLS everywhere + fine-grained traffic management per service instance. Not before — Istio operational complexity is significant.
- **"How do you migrate from Spring Cloud Gateway to Kong/Envoy?"** → Strangler fig: run both in parallel, shift traffic route by route. Start with least critical routes. Maintain feature parity before shifting high-traffic routes. Never big-bang migration.
