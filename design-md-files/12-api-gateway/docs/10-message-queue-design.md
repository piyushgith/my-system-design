# 10 — Message Queue Design: API Gateway

---

## Objective

Define Kafka usage in an API gateway context — primarily for async event publishing (access logs, metrics, audit events, config change propagation) rather than request routing, which remains synchronous.

---

## Why Kafka (Not Sync) for Gateway Events

The API gateway handles thousands of requests per second. For each request, it produces:

- Access log entry (1 per request)
- Metrics data points (latency, status code, upstream service)
- Audit event (for auth-sensitive endpoints)
- Rate limit update (to Redis, not Kafka)
- Distributed tracing span (to Jaeger/Tempo, not Kafka)

**Synchronous logging would block request handling**: at 100K RPS, if each log write takes 1ms → 100 CPU-seconds wasted per second on logging alone. Async Kafka publishing: fire-and-forget, non-blocking.

---

## Topics Produced by Gateway

```
gateway.access-log          (published per request)
gateway.auth-event          (published per auth success/failure)
gateway.rate-limit-exceeded (published per 429 response)
gateway.circuit-breaker     (published on circuit state change)
gateway.config-change       (consumed by gateway — see below)
gateway.api-key-revocation  (consumed by gateway — see below)
```

---

## Topic Design

### gateway.access-log

Produced by: every gateway pod, every request.

```json
{
  "requestId": "uuid",
  "timestamp": "ISO-8601",
  "clientIp": "203.x.x.x",
  "userId": "usr-123",
  "apiKeyId": "apikey-456",
  "method": "GET",
  "path": "/api/orders",
  "queryString": "page=1&limit=20",
  "statusCode": 200,
  "upstreamService": "order-service",
  "upstreamLatencyMs": 145,
  "gatewayLatencyMs": 8,
  "rateLimitRemaining": 87,
  "traceId": "trace-abc123",
  "userAgent": "MyApp/2.1.0"
}
```

Partition key: `upstreamService` (all order-service access logs on same partition for analytics).

Consumers:
- ELK indexer: index to Elasticsearch for querying
- Analytics service: aggregate by service, method, status code
- Billing service: count API calls per API key for usage-based billing
- Security analytics: detect anomalous patterns (IP, user, endpoint)

### gateway.auth-event

Published for: every login attempt, token validation failure, unauthorized access attempt.

```json
{
  "eventType": "AUTH_FAILURE",
  "timestamp": "ISO-8601",
  "clientIp": "203.x.x.x",
  "reason": "TOKEN_EXPIRED",
  "path": "/api/orders",
  "userId": null,
  "traceId": "trace-abc123"
}
```

Consumers:
- Security service: brute force detection (many AUTH_FAILURE from same IP → block)
- Audit service: security audit log

### gateway.rate-limit-exceeded

Published on every 429 response:

```json
{
  "timestamp": "ISO-8601",
  "clientIp": "203.x.x.x",
  "userId": "usr-123",
  "limitType": "USER_RATE_LIMIT",
  "path": "/api/orders",
  "currentRate": 105,
  "limitThreshold": 100
}
```

Consumers:
- Security service: persistent rate limit violations → potential abuse → IP block
- Alerting: high rate of 429s on specific endpoint → alert engineering

---

## Topics Consumed by Gateway

Gateway needs to react to external events without restart:

### gateway.config-change

Published by: Config Management Service on route config change.

```json
{
  "changeType": "ROUTE_UPDATED",
  "routeId": "orders-api",
  "timestamp": "ISO-8601",
  "newConfig": { ... }
}
```

Gateway consumer (1 consumer group per pod):
- On receive: hot-reload route config in memory
- Atomic swap: new config replaces old atomically (no request-handling downtime)
- All pods receive same event → eventually consistent config across pods

### gateway.api-key-revocation

Published by: Auth service on API key revocation.

```json
{
  "apiKeyId": "apikey-456",
  "keyHash": "sha256-of-key",
  "revokedAt": "ISO-8601"
}
```

Gateway consumer:
- On receive: DEL Redis key `apikey:{keyHash}`
- Effect: next request with this key hits DB (miss), finds it's revoked → 401

---

## Async Access Log Architecture

Access logging at 100K RPS requires careful design:

### Problem

Naive approach: write log to disk or Kafka synchronously per request → I/O blocks request handler.

### Solution: In-Memory Buffer + Batch Publish

```
Request handler:
  1. Add log entry to in-memory ring buffer (lock-free, wait-free) → 10ns
  2. Return response to client immediately

Background thread (per pod):
  1. Drain buffer every 100ms
  2. Batch publish to Kafka (100ms of logs in one batch)
  3. On Kafka unavailable: drop oldest logs or write to disk buffer
```

**Trade-off**: if gateway crashes, last 100ms of logs lost. Acceptable for access logs (not financial audit logs).

**Batch size**: at 100K RPS, 100ms = 10,000 log entries per batch. Kafka handles this easily.

### Kafka Producer Config for Access Logs

```
acks=1          # Leader ack only — fast; some loss on leader failure acceptable
compression.type=lz4  # Access logs compress 10:1 (repetitive structure)
batch.size=65536       # 64KB batches
linger.ms=100          # Wait 100ms to fill batch
```

`acks=1` acceptable for access logs (performance over durability). For audit events: `acks=all`.

---

## Distributed Tracing Integration

Traces are not published to Kafka — they go directly to Jaeger/Tempo:

```
Request arrives at gateway:
  1. Generate or propagate traceId (from incoming X-Trace-ID header or generate new)
  2. Create root span: start time, service = "gateway"
  3. Add to request context

Forward to backend:
  4. Inject trace headers: traceparent, X-Trace-ID, X-Span-ID

Backend processes request:
  5. Backend creates child span under gateway's root span

Gateway receives response:
  6. Complete root span: end time, status code, upstream latency

Export:
  7. Export spans to Jaeger via OpenTelemetry SDK (gRPC, not Kafka)
  → Jaeger provides trace visualization
```

Why not Kafka for traces? Jaeger/Tempo have purpose-built ingestion that handles trace spans better. Low-volume (sampled traces), latency-sensitive (need fast UI). Kafka overkill.

---

## API Usage Metering via Kafka

For billing (usage-based APIs):

```
gateway.access-log → billing-consumer:
  Filter: only requests with api_key (not user JWT)
  Aggregate: per api_key, per day, count requests
  Write to: billing_usage table in DB
  
  Billing cycle:
    Daily aggregation → invoice at month end
    Alert: when API key usage > 80% of plan limit
    Auto-upgrade: tier upgrade offer at 90% of limit
```

This Kafka-based metering decouples billing logic from request handling. Gateway doesn't know about billing plans — it just publishes access logs.

---

## Topic Configuration

| Topic | Partitions | Retention | Replication | Acks |
|---|---|---|---|---|
| gateway.access-log | 32 | 7 days | 3 | 1 (performance) |
| gateway.auth-event | 8 | 30 days | 3 | all (security) |
| gateway.rate-limit-exceeded | 8 | 7 days | 3 | 1 |
| gateway.circuit-breaker | 4 | 30 days | 3 | all |
| gateway.config-change | 4 | 7 days | 3 | all |
| gateway.api-key-revocation | 4 | 30 days | 3 | all |

High partition count for `gateway.access-log`: 100K RPS → 32 partitions × 3K RPS/partition = scalable.

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Async access log buffer | No I/O blocking requests | Last 100ms logs lost on crash |
| `acks=1` for access logs | Fast publish | Log loss on Kafka leader failure |
| `acks=all` for auth/audit | No security event loss | Higher publish latency |
| Config change via Kafka | Hot reload without restart | Config propagation delay (seconds) |
| API key revocation via Kafka | Decoupled | Up to consumer lag before effective |

---

## Interview Discussion Points

- **"Why use Kafka for access logs instead of writing to disk?"** → At 100K RPS, disk I/O serializes request handling. Kafka: async, batched, durable. ELK can consume Kafka directly. Disk is also not distributed — hard to query across pods.
- **"How do you propagate config changes to all gateway pods?"** → Kafka consumer group (one consumer per pod). All pods subscribe to `gateway.config-change`. On event: hot-reload config atomically in memory. Eventual consistency — pods may be up to seconds apart in seeing the change.
- **"How do you handle Kafka being down for access log publishing?"** → In-memory ring buffer absorbs spikes (100ms window). On extended Kafka outage: write to local disk buffer (fallback). Access logs are non-critical — some loss acceptable vs blocking requests.
- **"How do you count API calls for billing?"** → Kafka consumer reads `gateway.access-log`, filters API key requests, aggregates by key + day, writes to billing table. Exactly-once via idempotent consumer (ON CONFLICT DO NOTHING on billing_usage). Invoice generated at month-end from billing_usage.
- **"What's the latency impact of Kafka publishing?"** → Zero — async, fire-and-forget to in-memory buffer. Background thread publishes to Kafka every 100ms. Request handler is never blocked by Kafka.
