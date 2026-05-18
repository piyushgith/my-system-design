# 12 — Observability: API Gateway

---

## Objective

Define observability strategy for an API gateway — the highest-leverage monitoring point in a microservices system, since every request flows through it and every failure is visible here first.

---

## Why Gateway Observability is Uniquely Valuable

The API gateway is the best place in the system to observe:

- **Every external request**: one place to see all API traffic
- **Early failure detection**: gateway sees errors before users report them
- **Service health**: gateway circuit breakers reflect backend health
- **Client behavior**: user agents, API key usage, geographic distribution
- **Security signals**: auth failures, rate limit violations, suspicious patterns

---

## Metrics

### Tier 1: User-Facing Health (P1 Alert)

| Metric | Alert Threshold | Severity |
|---|---|---|
| Gateway error rate (5xx) | > 0.1% for 5 minutes | P1 |
| Gateway p99 latency | > 500ms overhead | P1 |
| Requests per second | Drop > 50% from 15-min avg | P1 |
| Circuit breaker open | Any circuit OPEN | P1 |
| Authentication failure rate | > 5% of auth requests | P1 |

### Tier 2: Operational (P2 Alert)

| Metric | Alert Threshold |
|---|---|
| Redis latency (rate limit) | p99 > 5ms |
| JWT cache miss rate | > 30% (suggests token churn) |
| Gateway CPU | > 80% avg over 10 minutes |
| Gateway pod count | < minimum pod count |
| Rate limit exceeded rate | > 5% of requests |

### Tier 3: Business / Developer Metrics (Dashboard)

- API calls per endpoint per day
- Top 10 APIs by volume
- API call distribution by client type (mobile, web, partner)
- API key usage vs plan limit (for billing)
- Geographic distribution of requests
- HTTP method distribution (GET/POST/PUT/DELETE ratio)

### RED Method per Service Route

For each backend route (not just global metrics):

```
Route: /api/orders/*
  Rate: 2,340 req/min
  Errors: 0.02% (5xx rate)
  Duration: p50=120ms, p95=340ms, p99=780ms

Route: /api/payments/*
  Rate: 432 req/min
  Errors: 0.00%
  Duration: p50=450ms, p95=1200ms, p99=2100ms

Route: /api/catalog/*
  Rate: 8,200 req/min
  Errors: 0.00%
  Duration: p50=45ms, p95=120ms, p99=280ms (mostly CDN cache hits)
```

This per-route breakdown immediately identifies which service is problematic.

---

## Distributed Tracing

### Gateway as Trace Root

Every request starts a trace at the gateway:

```
POST /api/orders
  └── [gateway] (root span: 8ms overhead)
       └── [order-service] (child span: 145ms)
            ├── [order-service DB] (child span: 12ms)
            ├── [inventory-service] (child span: 23ms)
            └── [payment-service] (child span: 98ms)
```

Gateway adds:
- `traceparent` header (W3C Trace Context)
- `X-Trace-ID` header (internal format)
- Root span: starts at gateway receives request, ends at gateway sends response
- Tags: `http.method`, `http.url`, `http.status_code`, `peer.service`

### Trace Propagation

```
Gateway receives request:
  1. Check incoming traceparent header (from client SDK — optional)
  2. If present: use as parent span
  3. If absent: generate new trace ID
  4. Add traceparent + X-Trace-ID headers to upstream request
  5. Backend propagates to its dependencies

All spans appear in Jaeger under same trace ID:
  → Full request flow visible in one view
  → Bottleneck identification immediate
```

### What to Trace

| Condition | Sampling Rate |
|---|---|
| Any 5xx response | 100% |
| p99 latency exceeded (> 2s) | 100% |
| Auth failure | 100% |
| Payment route | 100% |
| Normal successful request (< 200ms) | 1% |
| Normal successful request (200ms-1s) | 10% |

---

## Access Log Strategy

### Log Format

```json
{
  "ts": "2026-01-15T10:23:45.123Z",
  "reqId": "uuid",
  "method": "GET",
  "path": "/api/orders",
  "qs": "page=1",
  "status": 200,
  "userId": "usr-123",
  "apiKeyId": null,
  "clientIp": "203.x.x.x",
  "userAgent": "MobileApp/3.1.0",
  "upstream": "order-service",
  "upstreamLatencyMs": 145,
  "gwLatencyMs": 8,
  "totalLatencyMs": 153,
  "rateLimitRemaining": 87,
  "traceId": "trace-abc123",
  "cached": false,
  "circuitState": "CLOSED"
}
```

### Log Pipeline

```
Gateway pods (async) → Kafka: gateway.access-log
    ↓
Logstash / Fluentd consumer
    ↓
Elasticsearch (7-day hot storage) → Kibana dashboards
    ↓
S3 (cold storage: 90 days) → Athena for ad-hoc queries
```

Elasticsearch hot storage: only 7 days (expensive). S3: cheap long-term. Athena: SQL on S3 for post-incident analysis.

---

## Security Observability

### Auth Failure Analysis

```
Dashboard: Auth Failures by Type
  TOKEN_EXPIRED: 1,234 (normal — users' tokens expire)
  INVALID_SIGNATURE: 23 (potential attack — monitor for spikes)
  MISSING_TOKEN: 456 (clients calling protected endpoints without auth)
  FORBIDDEN: 89 (authenticated users calling unauthorized endpoints)
  
Alert: INVALID_SIGNATURE > 100/min from same IP → potential credential attack
```

### Rate Limit Violation Analysis

```
Dashboard: Rate Limit Violations
  By userId: who is hitting limits most
  By IP: bot or aggressive client
  By endpoint: which endpoint is being hammered
  By time: flash sale traffic? Legitimate spike?
  
Alert: Single IP responsible for > 50% of rate limit violations → investigate
```

### Anomaly Detection

```
Machine learning on access logs:
  - Unusual request pattern for userId (different country, time, endpoint)
  - Sudden endpoint popularity (scraping attack or viral content)
  - API key usage spike (key compromised)
  
These feed security incident response system
```

---

## Circuit Breaker Observability

Circuit breaker state changes are critical operational events:

```
Metric: gateway_circuit_breaker_state{service="order-service"}
  0 = CLOSED (healthy)
  1 = HALF_OPEN
  2 = OPEN (service down)

Alert: circuit OPEN for > 60 seconds → P1 (backend service has sustained failure)

Circuit open/close events logged to Kafka (gateway.circuit-breaker):
  {
    "service": "order-service",
    "fromState": "CLOSED",
    "toState": "OPEN",
    "triggerReason": "5 failures in 10 requests",
    "timestamp": "..."
  }
```

Dashboard shows:
- Circuit state per service (last 24 hours)
- Circuit open events timeline
- Correlation with deployment events (canary deployment → circuit opens?)

---

## SLI / SLO

### Gateway SLIs

| SLI | Measurement |
|---|---|
| Availability | % of requests returning non-5xx (from external monitoring) |
| Latency | p99 gateway overhead (gateway_latency, not upstream_latency) |
| Error budget burn rate | How fast is error budget consumed vs expected |

### SLOs

| SLI | Target |
|---|---|
| Gateway availability | 99.99% (52 min/year downtime allowed) |
| Gateway p99 latency overhead | < 30ms |
| Auth success rate | > 99.9% of valid auth requests succeed |
| Rate limit accuracy | Rate limiting errors < 0.1% |

### Error Budget Tracking

```
Monthly error budget: 99.99% → 0.01% errors allowed
= 0.01% × 30 days × 86,400 s/day × 100K RPS = 2,592 allowed error-seconds

Current burn rate (last 24h): 3 error-seconds/day
Budget remaining: 2,592 - (3 × 5 days) = 2,577 seconds
Budget % remaining: 99.4% (healthy)

Alert: If budget burn rate > 3× expected → error budget alert → freeze deployments
```

---

## Dashboards

### Real-Time Operations Dashboard

```
┌──────────────────────────────────────────────────────────────┐
│   RPS        │  Error Rate %  │  P99 Latency  │  Auth OK %  │
│  89,234      │    0.02%       │    287ms       │   99.95%   │
├──────────────┼────────────────┼───────────────┼─────────────┤
│  Circuit     │  Redis Hit %   │  Pod Count    │  Rate 429s │
│  All CLOSED  │    94.2%       │     8         │   0.3%     │
├──────────────┴────────────────┴───────────────┴─────────────┤
│  [RPS Graph]  [Error Rate]  [Latency P99]  [Circuit States] │
│  [Auth Failure Type]  [Rate Limit Violations]               │
└──────────────────────────────────────────────────────────────┘
```

### Per-Service Dashboard

For each backend service (drill-down from main dashboard):
- RPS routed to service
- Error rate from service
- p50, p95, p99 latency
- Circuit breaker state history
- Canary traffic split (if active deployment)

### Security Dashboard

- Auth failures by type (last 1h, 24h, 7d)
- Top IPs by request volume (potential bots)
- Unusual geographic distribution
- API key usage anomalies

---

## Synthetic Monitoring

Run synthetic requests through the full gateway:

```
Every 60 seconds:
  1. GET /health → expect 200
  2. GET /api/public/config → expect 200
  3. POST /auth/login (test user) → expect 200 + JWT
  4. GET /api/orders (with JWT) → expect 200 or 204
  5. GET /api/search?q=test → expect 200

→ If any step fails: P1 alert
→ Measures end-to-end latency from external perspective
→ Proves auth pipeline working (not just infrastructure)
```

Run from: multiple geographic locations (Mumbai, Singapore, US-East) → validates regional gateway health.

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Async access logging | No I/O blocking | Last 100ms logs lost on crash |
| Per-route RED metrics | Precise service attribution | Cardinality: N routes × 4 metrics × 10 labels |
| 100% trace sampling for errors | Full visibility on failures | Storage cost (mitigate with head-based sampling for successes) |
| Circuit breaker state in metrics | Immediate service health visibility | Per-pod state (inconsistency across pods is OK) |
| Synthetic monitoring | Proactive detection | False positive risk on expected 4xx responses |

---

## Interview Discussion Points

- **"How do you detect a backend service failure before users report it?"** → Circuit breaker metric: `gateway_circuit_breaker_state{service="X"}` = OPEN → P1 alert. Also: error rate per route spikes within seconds of backend failure.
- **"What's the most valuable metric at the gateway?"** → Error rate per route + circuit breaker state. These immediately tell you which service is sick. p99 latency tells you which service is slow. RED method per route = complete health picture.
- **"How do you correlate a user complaint to a specific request?"** → Request ID in access log. User provides: approximate time, action they took. Query Elasticsearch: `userId=X AND timestamp=~T AND path=~Y` → find request → join with trace in Jaeger by traceId → see full request flow.
- **"How do you know if your canary deployment is healthy?"** → Compare RED metrics: canary pods vs stable pods. Error rate higher on canary → rollback. Canary pod label in Prometheus allows split view.
- **"How do you detect API scraping?"** → Access log analysis: single userId or IP with very high volume on specific endpoints. Unusual user agent. Requests significantly faster than human interaction speed. Alert + CAPTCHA or rate limit.
