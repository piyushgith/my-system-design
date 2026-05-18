# 16 — Advanced Improvements: API Gateway

---

## Objective

Define advanced capabilities that elevate an API gateway from infrastructure to platform — covering GraphQL federation, service mesh integration, ML-based traffic management, developer experience, and the architectural refinements that distinguish world-class API platforms.

---

## 1. GraphQL Federation Gateway

### Problem

Each team builds their own GraphQL schema. Clients must query multiple endpoints.

### Advanced Solution: Federated GraphQL Gateway

```
Gateway exposes: single GraphQL endpoint (/graphql)

Behind the scenes:
  Query: { user { name }, orders { total }, products { title } }
    → Gateway splits: 
        user fields    → user-service GraphQL
        orders fields  → order-service GraphQL
        products fields → catalog-service GraphQL
    → Merge responses into single result
    → Return unified response to client

Implementation: Apollo Federation or GraphQL Mesh
```

**Benefits**:
- Client makes one request (not 3)
- Each team owns their schema independently
- Schema stitching at gateway level — services don't know about each other

**Challenges**:
- N+1 problem across services (DataLoader pattern mandatory)
- Schema evolution must be backward-compatible across all federated services
- Debugging distributed GraphQL queries is complex (tracing spans across services)

**When to adopt**: when mobile/web clients aggregate data from 5+ services per screen. Reduces round trips significantly.

---

## 2. Service Mesh Integration (Istio + Envoy)

### Current State (Phases 0-2)

Gateway handles: auth, routing, rate limiting for north-south traffic.
Inter-service communication: direct HTTP calls, no mTLS, no circuit breaking per instance.

### Advanced: Gateway + Service Mesh

```
External traffic → API Gateway (north-south)
                      │
                      ▼ (HTTP internal)
Inter-service traffic → Istio service mesh (east-west)
  - mTLS: all service-to-service encrypted
  - Circuit breaking: per Envoy sidecar instance
  - Retries: intelligent retry with backoff
  - Traffic splitting: per instance (not just per deployment)
  - Observability: Istio auto-injects telemetry

Gateway role shifts:
  Before mesh: gateway handles auth + routing + circuit breakers + retries
  With mesh:   gateway handles auth + routing; mesh handles circuit breakers + retries + mTLS
```

**Split of responsibilities with service mesh**:

| Concern | Without Mesh | With Mesh |
|---|---|---|
| Auth (external) | Gateway | Gateway |
| Auth (internal) | Each service | mTLS via Istio (automatic) |
| Circuit breaking | Gateway (per service) | Envoy sidecar (per instance) |
| Retries | Gateway | Envoy sidecar |
| Load balancing | Gateway | Envoy sidecar |
| Observability | Gateway + custom | Istio auto-injects |
| Traffic splitting | Gateway | Istio VirtualService |

**When to add Istio**: 10+ services, security requires mTLS everywhere, need per-instance circuit breaking (gateway only does per-service).

**Operational cost**: Istio adds ~100ms startup delay per pod (sidecar injection), ~10MB memory overhead per pod, learning curve for engineers.

---

## 3. ML-Based Traffic Management

### Intelligent Routing

Beyond static route config:

```
Request arrives → ML scoring model:
  Features:
    - Time of day (traffic patterns differ)
    - Geographic origin
    - User tier (free/paid/enterprise)
    - Recent backend health metrics
    - Current load per backend instance
    
  Output: which backend instance to route to (not just round-robin)
  
  Benefits:
    - Route paid users to faster instances
    - Route to instances with lower latency history
    - Pre-warm instances for geographic events (Diwali: more Indian traffic)
```

### Bot Detection at Gateway

```
ML model per request (< 5ms inference):
  Features:
    - Request rate vs human typing speed
    - JavaScript fingerprint (bot vs browser)
    - Mouse movement patterns (behavioral biometrics)
    - IP reputation
    - User agent consistency
    - Request timing patterns (human vs script-like)
    
  Score 0-1:
    < 0.3: human → allow
    0.3-0.7: suspicious → CAPTCHA challenge
    > 0.7: bot → block + log
```

Real-time feature computation: Redis Streams with Flink for velocity features. Model served via TensorFlow Serving or TorchServe sidecar.

### Anomaly Detection for API Abuse

```
Detect unusual patterns:
  - New endpoint suddenly 100x traffic → scraping attack or viral content
  - API key calling endpoints outside its declared scope
  - Unusual response size patterns (data exfiltration attempt)
  
Actions:
  - Alert security team
  - Throttle automatically
  - Block and require manual review
```

---

## 4. Developer Experience Platform

### API Catalog and Documentation

```
Auto-generated from OpenAPI specs:
  Backend service exposes: /api-docs (Springdoc OpenAPI)
  Gateway aggregates: all service OpenAPI specs → unified catalog
  Developer portal: browse APIs, try requests inline, generate SDK

Features:
  - Interactive API explorer (Swagger UI + RapiDoc)
  - Code samples in multiple languages (Java, Python, JavaScript, Go)
  - Changelog (auto-generated from schema diffs)
  - Deprecation notices with migration guides
  - Versioned docs (v1, v2, v3 side by side)
```

### SDK Generation

```
OpenAPI spec → SDK generator (OpenAPI Generator)
  → Java SDK
  → Python SDK
  → JavaScript/TypeScript SDK
  → Go SDK

Published to:
  → Maven Central (Java)
  → PyPI (Python)
  → npm (JavaScript)
  
Auto-updated on API change:
  Schema change → CI generates new SDK → publishes to package registries
  → Developer receives: "API client updated: v2.3.1"
```

### Sandbox Environment

```
developer.api.yourplatform.com (sandbox):
  - Same gateway config as production
  - Mock backends (return realistic test data)
  - Test API keys with no rate limits
  - Request logging visible to developer in portal
  - No real data, no real payments
  
Developer workflow:
  1. Register for developer account
  2. Create API keys (test mode)
  3. Explore APIs in sandbox
  4. Build and test integration
  5. Request production access
  6. Launch
```

---

## 5. Advanced Traffic Management

### Canary Analysis (Automated)

```
Deploy: gateway-v2 at 5% canary

Automated canary analysis (Flagger or Argo Rollouts):
  Collect metrics for 30 minutes:
    - Error rate: canary vs baseline
    - p99 latency: canary vs baseline
    - Custom metric: auth_success_rate

  Statistical analysis:
    Mann-Whitney U test: is canary significantly worse?
    
  Decision:
    Not worse → promote to 25%
    Worse → rollback to 0%
    
Human never involved unless manual override needed
```

### Progressive Delivery

```
Feature flag integration at gateway level:
  Request has header X-User-Id: user123
  Gateway checks: is user123 in "new-checkout-flow" feature flag cohort?
  Yes → route to checkout-service-v2
  No → route to checkout-service-v1

Feature flags source: LaunchDarkly / Unleash
Gateway fetches flag state: in-memory cache, refreshed every 30s

Benefits:
  - Dark launch: deploy code without routing traffic to it
  - Gradual rollout: 1% → 10% → 100% by user cohort
  - Instant rollback: flip flag, not code deployment
```

---

## 6. API Gateway as Event Hub

### Webhooks Management

Centralize webhook lifecycle at gateway:

```
Merchant registers webhook:
  POST /webhooks { url: "https://merchant.com/hook", events: ["order.placed", "payment.succeeded"] }

Gateway manages:
  - Delivery: POST to merchant URL on event
  - Retry: exponential backoff (1s, 5s, 30s, 5min, 1h, 24h)
  - Signing: HMAC-SHA256 signature on payload
  - Delivery log: stored per webhook, queryable by merchant
  - Pause: automatic pause after 72h failure streak
  - Replay: merchant can replay any webhook from last 7 days

Kafka integration:
  Event topic → gateway webhook worker → HTTP POST to merchant URL
  Per merchant: dedicated queue slot (one merchant's slow endpoint doesn't affect others)
```

### Server-Sent Events (SSE) Gateway

```
Client connects: GET /api/events (SSE)
  → Gateway establishes SSE connection
  → Gateway subscribes to Kafka topic on behalf of client
  → On Kafka event matching userId: push via SSE
  → Client receives real-time updates without polling

Scale:
  Long-lived SSE connections: 1M concurrent clients
  Gateway holds: 1M open HTTP connections
  Memory: ~10KB per connection = 10GB for 1M (need dedicated SSE gateway pods)
```

---

## 7. Multi-Protocol Gateway

### gRPC-Web Transcoding

```
Browser (HTTP/1.1) → Gateway → Backend (gRPC / HTTP/2)

Gateway handles:
  - HTTP/1.1 POST → HTTP/2 gRPC call
  - JSON body → Protobuf serialization
  - HTTP response → JSON deserialization

Benefit: browsers can call gRPC services without gRPC-web stub
Tools: Envoy gRPC-JSON transcoding, grpc-gateway
```

### WebSocket Load Balancing

```
Problem: WebSocket = long-lived connection → sticky session needed

Solution:
  WebSocket connection → Gateway → sticky to one backend pod (consistent hash on userId)
  
  If backend pod dies:
    Client reconnects → gateway routes to new pod
    New pod: load session state from Redis
    Client: transparent reconnect (auto-reconnect in SDK)

Scale:
  10K concurrent WebSocket connections per gateway pod
  1M connections = 100 gateway pods (WebSocket-specific pod pool)
```

---

## 8. Architecture Self-Critique

### Weaknesses

| Weakness | Risk | Mitigation |
|---|---|---|
| Single gateway for all traffic | Bottleneck as scale grows | BFF pattern; dedicated gateway clusters per traffic type |
| Rate limit Redis latency | Adds 1-2ms per request | Redis co-located in same AZ; Redis cluster for HA |
| In-memory circuit breaker | Per-pod inconsistency | Acceptable; service mesh handles per-instance at scale |
| Config propagation delay (Kafka) | Seconds to apply route change | Acceptable for config; not for security events (revocation needs faster path) |
| JWT cache invalidation gap | 5-min window for revoked tokens | Shorter TTL for high-security APIs; revocation list for compromised tokens |

### Scaling Limits

| Component | Current Limit | Next Step |
|---|---|---|
| Spring Cloud Gateway | ~50K RPS per pod | Kong (Nginx-based) at 200K RPS; Envoy at 1M+ |
| Redis rate limiting | 300K ops/sec (3-node cluster) | Shard by user_id range across more Redis nodes |
| Access log Kafka | 1M msgs/sec per topic | More partitions; dedicated log cluster |
| JVM heap (route config) | ~2GB (100K routes) | Offload to external config store |

### Tech Debt Risks

1. **Business logic accumulation**: teams add business logic to gateway filters over time ("just add it in the gateway"); eventually gateway knows about every domain
2. **Version sprawl**: API versions accumulate; v1, v2, v3, v4 all live simultaneously; gateway must support all; backend services must maintain all versions
3. **Plugin hell** (Kong): dozens of plugins enabled per route; complex interaction effects; hard to test
4. **Config drift**: route config in YAML and route config in DB (portal) diverge; single source of truth needed

### Taking Interviewer Challenges

- *"How does Netflix/Google handle gateway at their scale?"* → Custom Envoy-based gateway; dedicated edge PoPs; Lua/WASM filters for custom logic; thousands of RPS per PoP; not Spring Cloud Gateway
- *"What happens when your gateway needs to aggregate data from 5 services for one mobile screen?"* → BFF (Backend for Frontend) pattern: dedicated aggregation service per client type; NOT business logic in gateway; gateway routes to BFF
- *"How do you handle WebSocket connections at 1M concurrent users?"* → Dedicated WebSocket gateway pod pool; sticky sessions per userId; connection state in Redis; backend pods stateless (load state on reconnect)
- *"What's the difference between your API gateway and a service mesh? Why have both?"* → Gateway: north-south (external auth, routing, rate limiting, developer portal). Mesh: east-west (mTLS, per-instance circuit breaking, retries). Different concerns, different traffic. At scale you need both.
- *"How do you prevent the gateway from becoming a monolith of business logic?"* → Strict governance: gateway allowed = auth, routing, rate limiting, protocol translation, observability. Not allowed = data transformation, business rules, orchestration. Review PRs that add business logic to gateway; reject + redirect to BFF service.
