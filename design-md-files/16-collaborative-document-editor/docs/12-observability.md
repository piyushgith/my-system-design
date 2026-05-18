# 12 — Observability

## Objective
Define the logging, metrics, tracing, alerting, and SLI/SLO framework for the Collaborative Document Editor. Observability must cover both the infrastructure layer and the domain-specific collaboration semantics (op lag, transform errors, convergence health).

---

## Observability Stack

| Concern | Tool |
|---|---|
| Metrics collection | Prometheus + custom exporters |
| Metrics visualization | Grafana dashboards |
| Log aggregation | Elasticsearch (ELK stack) via Filebeat |
| Distributed tracing | OpenTelemetry + Jaeger / Tempo |
| Alerting | Alertmanager + PagerDuty |
| Synthetic monitoring | Grafana k6 (real user simulation) |
| Error tracking | Sentry (frontend + backend) |
| Infrastructure APM | Datadog or New Relic (optional for teams preferring SaaS) |

---

## SLI / SLO / SLA Definitions

### SLI (Service Level Indicators)

| SLI | Measurement Method |
|---|---|
| Op propagation latency (p99) | Time from op received by WS Gateway to broadcast to all connected clients |
| Document open latency (p95) | Time from GET /documents/{id} request to full response |
| WebSocket connection success rate | Successful WS connections / total WS connection attempts |
| Op acceptance rate | Ops successfully sequenced / total ops submitted |
| Snapshot availability | Successful snapshot fetches / total snapshot fetch attempts |

### SLO (Service Level Objectives)

| SLO | Target | Measurement Window |
|---|---|---|
| Op propagation p99 < 500 ms | 99.9% of 5-min windows | Rolling 28 days |
| Document open p95 < 300 ms | 99.5% of 5-min windows | Rolling 28 days |
| WS connection success rate | > 99.9% | Rolling 28 days |
| Op acceptance rate | > 99.99% | Rolling 28 days |
| Service availability (editing) | > 99.95% | Rolling 30 days |

### Error Budget
- 99.95% availability = 21.6 minutes downtime/month error budget
- Error budget consumption tracked in real-time on SLO dashboard
- If error budget < 20% remaining: feature freezes; all effort goes to reliability
- If error budget > 80% remaining: new feature deployments permitted

---

## Key Metrics by Service

### Collaboration Service Metrics
```
# Op processing
collab_ops_received_total{document_id, author_id}
collab_ops_applied_total{document_id}
collab_ops_rejected_total{document_id, reason}
collab_ops_transform_duration_seconds{document_id}   ← histogram

# Document state
collab_active_documents_gauge
collab_ops_since_snapshot_gauge{document_id}          ← alert if > 5000
collab_transform_queue_depth_gauge

# Session management
collab_active_sessions_gauge{document_id}
collab_session_established_total
collab_session_terminated_total{reason}

# Convergence health
collab_fingerprint_mismatch_total{document_id}        ← CRITICAL: alert immediately
```

### WebSocket Gateway Metrics
```
ws_connections_active_gauge
ws_connections_established_total
ws_connections_dropped_total{reason}
ws_message_receive_rate
ws_message_send_rate
ws_op_publish_latency_seconds{document_id}            ← histogram
ws_fanout_latency_seconds                             ← histogram
ws_reconnect_attempts_total
```

### Document Service Metrics
```
doc_open_latency_seconds{cache_hit}                   ← histogram, split by cache hit/miss
doc_snapshot_cache_hit_rate
doc_snapshot_load_from_s3_total
doc_op_replay_count_histogram{document_id}            ← alert if > 1000 ops replayed
doc_creates_total
doc_deletes_total
```

### Kafka Consumer Metrics
```
kafka_consumer_lag{group, topic, partition}           ← alert if > 50K
kafka_consumer_records_processed_total{group, topic}
kafka_consumer_processing_latency_seconds{group}
kafka_dlq_messages_total{topic}                       ← alert if > 0
```

### Database Metrics
```
pg_query_duration_seconds{query_type}                 ← histogram
pg_connections_active
pg_replication_lag_seconds{replica}                   ← alert if > 5s
pg_op_log_insert_rate
redis_memory_usage_bytes
redis_cache_hit_rate{key_pattern}
redis_pub_sub_messages_per_second
```

---

## Distributed Tracing

Every request carries a `traceId` (OpenTelemetry W3C TraceContext format) propagated through:
- HTTP headers: `traceparent: 00-{traceId}-{spanId}-01`
- Kafka message headers: `traceId` field
- Redis calls: annotated with span metadata (not propagated natively; context preserved in span)

### Critical Trace Paths

**Trace 1: Op from client to broadcast**
```
Span: ws-gateway.receive-op
  Span: collab-service.transform-op
    Span: collab-service.fetch-pending-ops (Redis ZRANGEBYSCORE)
    Span: collab-service.apply-transform
  Span: collab-service.kafka-publish
  Span: collab-service.redis-pubsub-publish
Span: ws-gateway.fan-out-to-clients
```
Target: total trace duration < 100 ms (p50); < 500 ms (p99)

**Trace 2: Document open**
```
Span: api-gateway.auth-check
Span: document-service.get-document
  Span: document-service.check-permission (Redis hit or miss → PG)
  Span: document-service.fetch-snapshot (Redis hit or miss → S3)
  Span: document-service.fetch-pending-ops (Redis or PG)
  Span: document-service.replay-ops
```

---

## Logging Strategy

### Log Format (structured JSON)
```json
{
  "timestamp": "2026-05-18T10:30:00.123Z",
  "level": "INFO",
  "service": "collaboration-service",
  "traceId": "abc123def456",
  "spanId": "789ghi012",
  "userId": "usr_alice",
  "documentId": "doc_xyz789",
  "event": "op_applied",
  "seq": 1043,
  "opType": "INSERT",
  "transformationCount": 2,
  "durationMs": 3.5,
  "podId": "collab-pod-abc"
}
```

### Log Levels and Retention

| Level | Use | Retention |
|---|---|---|
| DEBUG | Transform step details (disabled in prod by default) | 1 day |
| INFO | Normal operations: op applied, snapshot created, session started | 7 days |
| WARN | Non-critical anomalies: cache miss, transform retried, slow query | 30 days |
| ERROR | Op rejected, service dependency failure, unexpected exception | 90 days |
| AUDIT | Security events: permission changes, access grants, deletions | 1 year |

### Correlation IDs
Every log entry must include:
- `traceId` — from OpenTelemetry
- `requestId` — from API Gateway (for REST requests)
- `sessionId` — for WebSocket requests
- `documentId` — when known
- `userId` — when authenticated

These allow filtering all logs for a specific user session in Kibana in under 5 seconds.

---

## Alerting Rules

### P0 Alerts (Page immediately; wake on-call)
```yaml
- alert: CollaborationConvergenceFailure
  condition: rate(collab_fingerprint_mismatch_total[5m]) > 0
  severity: P0
  message: "Document state divergence detected — possible OT bug"

- alert: KafkaDLQMessages
  condition: kafka_dlq_messages_total > 0
  severity: P0
  message: "Messages in DLQ — ops may be lost"

- alert: ServiceAvailabilityBreach
  condition: slo_availability_ratio < 0.9995
  severity: P0
  message: "SLO availability below target"
```

### P1 Alerts (Page with 15-minute acknowledgment window)
```yaml
- alert: OpPropagationLatencyHigh
  condition: histogram_quantile(0.99, ws_op_publish_latency_seconds) > 1.0
  severity: P1

- alert: KafkaConsumerLagHigh
  condition: kafka_consumer_lag{group="postgres-batch-writer"} > 100000
  severity: P1

- alert: SnapshotServiceLagHigh
  condition: collab_ops_since_snapshot_gauge > 5000
  for: 10m
  severity: P1

- alert: RedisMemoryFull
  condition: redis_memory_usage_bytes / redis_memory_max_bytes > 0.85
  severity: P1
```

### P2 Alerts (Notify in Slack; fix within business day)
```yaml
- alert: DocumentOpenLatencyDegraded
  condition: histogram_quantile(0.95, doc_open_latency_seconds) > 0.5
  severity: P2

- alert: PostgreSQLReplicationLag
  condition: pg_replication_lag_seconds > 10
  severity: P2
```

---

## Dashboards

### Dashboard 1: Real-Time Collaboration Health
- Active WebSocket connections (per region)
- Op throughput (ops/sec)
- Op propagation latency (p50, p95, p99)
- Fingerprint mismatch rate (must be 0)
- Active document count
- Hot document list (top 10 by op rate)

### Dashboard 2: Document System Health
- Document open latency (p50, p95)
- Cache hit rates (snapshot cache, op buffer, permission cache)
- S3 read latency
- PostgreSQL query latency
- Op log insert rate

### Dashboard 3: Kafka Health
- Consumer lag per group
- DLQ message count (must be 0)
- Topic partition leader distribution
- Broker disk usage

### Dashboard 4: SLO Tracking
- Error budget remaining (visual burn-down)
- SLO compliance rate for each SLI
- Incident history (downtime events)

---

## Synthetic Monitoring

A Grafana k6 script simulates a realistic user journey every minute:
1. Authenticate as test user
2. Open a test document (measure latency)
3. Establish WebSocket connection
4. Submit 10 edit operations
5. Verify ops are echoed back with correct server seq
6. Verify second connected test user receives the ops within 500 ms
7. Close connection

This end-to-end synthetic test catches real-user-experience degradation that service-level metrics may miss.

---

## Collaboration-Specific Observability

Beyond standard metrics, unique collaboration health indicators:

| Metric | What It Reveals |
|---|---|
| Average ops-since-snapshot | Snapshot Service health; high value means slow document opens |
| OT transform chain length | How many concurrent ops clients are typically behind; high value suggests high-contention docs |
| Reconnect rate | Network quality / WS Gateway instability |
| Fingerprint mismatch count | OT correctness — this must always be 0 |
| Presence liveness ratio | Percentage of active sessions sending heartbeats |
| Offline sync success rate | Offline edit merge success; failures indicate OT edge cases |

---

## Interview Discussion Points
- How does distributed tracing help debug a case where one user sees a stale document state?
- Why is the `collab_fingerprint_mismatch_total` metric a P0 alert, and what does it mean for the business if it is non-zero?
- How do you measure "end-to-end op propagation latency" across client, WebSocket Gateway, Collaboration Service, and back to client?
- What is the relationship between error budget and feature deployment velocity, and how do you enforce it organizationally?
- How would you instrument an OT transform function to expose internal algorithm behavior without leaking document content in logs?
