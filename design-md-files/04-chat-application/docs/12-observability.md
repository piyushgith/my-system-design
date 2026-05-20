# 12 — Observability: Chat Application

---

## Objective

Define the logging, metrics, distributed tracing, alerting, and SLI/SLO strategy for a real-time chat platform operating at 1M messages/second. Make every component's behavior transparent without adding latency to the delivery path.

---

## Observability Pillars

| Pillar | Tool | Purpose |
|--------|------|---------|
| **Structured Logging** | ELK Stack (Elasticsearch + Logstash + Kibana) | Searchable event logs, debugging, audit |
| **Metrics** | Prometheus + Grafana | Real-time dashboards, alerting, capacity planning |
| **Distributed Tracing** | OpenTelemetry + Jaeger (or Tempo) | End-to-end latency analysis, bottleneck identification |
| **Real-time Alerting** | Alertmanager → PagerDuty | On-call escalation for SLO violations |
| **Chaos/Anomaly Detection** | Grafana anomaly detection | Detect unusual traffic patterns (DDoS, bot attacks) |

---

## Structured Logging

### Logging Principles

1. **No PII in logs**: User IDs are logged as opaque identifiers; message content is NEVER logged
2. **Structured JSON format**: Every log line is parseable (not free-text)
3. **Correlation ID**: Every request/event carries a `trace_id` that flows through all services
4. **Sampling for high-volume events**: Not every message send is logged at DEBUG level — sample at 1%

### Standard Log Fields

```json
{
  "timestamp": "2026-05-17T10:00:00.123Z",
  "level": "INFO",
  "service": "message-service",
  "instance_id": "pod-abc-123",
  "trace_id": "trace-xyz-001",
  "span_id": "span-001",
  "user_id": "user-001",
  "conversation_id": "conv-abc",
  "message_id": "msg-001",
  "sequence_num": 100,
  "event": "message_created",
  "duration_ms": 12,
  "cassandra_write_ms": 8,
  "kafka_publish_ms": 4
}
```

### Log Levels per Service

| Service | INFO | WARN | ERROR |
|---------|------|------|-------|
| WebSocket Server | Connection open/close, heartbeat stats | JWT validation failure | Connection crash |
| Message Service | Message created (sampled 1%) | Idempotency collision | Cassandra write failure |
| Fan-Out Service | Fan-out completed | Redis publish timeout | Fan-out DLQ write |
| Presence Service | Presence change | Heartbeat timeout | Redis failure |

### Log Retention

| Log Type | Retention | Storage |
|----------|----------|---------|
| Application logs (INFO) | 7 days (hot) | Elasticsearch cluster |
| Error/WARN logs | 30 days | Elasticsearch cluster |
| Audit logs (security events) | 2 years | S3 + Glacier |
| Access logs (API Gateway) | 30 days | S3 |

---

## Metrics

### WebSocket Server Metrics

| Metric | Type | Labels | Alert Threshold |
|--------|------|--------|----------------|
| `ws_connections_active` | Gauge | server_id | > 48K per server (96% capacity) |
| `ws_message_send_rate` | Counter | server_id | — |
| `ws_frame_send_latency_ms` | Histogram | server_id, frame_type | p99 > 100ms |
| `ws_connection_error_total` | Counter | server_id, error_type | Rate > 100/min |
| `ws_heartbeat_timeout_total` | Counter | server_id | Rate > 500/min |
| `ws_reconnect_rate` | Counter | server_id | Spike > 2x baseline |

### Message Service Metrics

| Metric | Type | Labels | Alert |
|--------|------|--------|-------|
| `message_send_total` | Counter | conv_type, content_type | — |
| `message_send_latency_ms` | Histogram | — | p99 > 100ms |
| `cassandra_write_latency_ms` | Histogram | — | p99 > 50ms |
| `kafka_publish_latency_ms` | Histogram | topic | p99 > 30ms |
| `message_send_error_total` | Counter | error_type | Rate > 100/min |
| `idempotency_dedup_total` | Counter | — | Spike > normal (client retry storm?) |
| `sequence_num_fallback_total` | Counter | — | Any occurrence → Redis issue |

### Fan-Out Service Metrics

| Metric | Type | Labels | Alert |
|--------|------|--------|-------|
| `fanout_lag_messages` | Gauge | topic, partition | > 10,000 messages |
| `fanout_latency_ms` | Histogram | conv_type | p99 > 500ms |
| `fanout_redis_publish_rate` | Counter | — | — |
| `fanout_offline_pushes_total` | Counter | — | — |
| `fanout_dlq_writes_total` | Counter | failure_reason | Any occurrence → alert |
| `fanout_group_size_distribution` | Histogram | — | Detect growth of very large groups |

### Presence Service Metrics

| Metric | Type | Labels | Alert |
|--------|------|--------|-------|
| `presence_online_users_total` | Gauge | region | — |
| `presence_heartbeat_rate` | Counter | — | Drop > 20% (WS servers losing connections?) |
| `presence_update_latency_ms` | Histogram | — | p99 > 2s |
| `presence_redis_latency_ms` | Histogram | operation | p99 > 5ms |

### Cassandra Metrics

| Metric | Labels | Alert |
|--------|--------|-------|
| Write latency (p50, p99) | table | p99 > 10ms → WARNING; p99 > 50ms → CRITICAL |
| Read latency | table | p99 > 20ms |
| Pending compactions | node | > 50 → capacity issue |
| Disk utilization | node | > 80% |
| Dropped messages | — | Any > 0 → alert |

### Kafka Metrics

| Metric | Labels | Alert |
|--------|--------|-------|
| Consumer lag | topic, consumer_group | > 10,000 → scale Fan-Out |
| Producer error rate | topic | > 0.1% |
| Broker disk utilization | broker_id | > 85% |
| Under-replicated partitions | — | Any > 0 → broker failure? |
| Leader election rate | — | > 10/min → instability |

---

## Distributed Tracing

Every user-facing request generates a trace that flows through all services:

```
Trace: "Alice sends a message"
│
├── Span: API Gateway (JWT validation) — 2ms
│
├── Span: WebSocket Server (frame received → gRPC call) — 5ms
│
├── Span: Message Service
│   ├── Span: Membership validation (Redis) — 1ms
│   ├── Span: Sequence number assign (Redis INCR) — 0.5ms
│   ├── Span: Cassandra write (QUORUM) — 8ms
│   └── Span: Kafka publish — 4ms
│
└── Span: Fan-Out Service
    ├── Span: Connection Registry lookup (Redis) — 1ms
    ├── Span: Redis Pub/Sub publish × N — 2ms
    └── Span: Offline notification publish (Kafka) — 3ms

Total end-to-end: ~26ms
```

### OpenTelemetry Integration

```
Trace propagation: W3C TraceContext header (trace-id, span-id)
  HTTP: traceparent header
  Kafka: custom header in ProducerRecord metadata
  WebSocket frames: frame metadata field

Sampling strategy:
  - 1% of all messages (too high volume for 100% sampling)
  - 100% of error traces
  - 100% of traces with p99 latency violations
  - 100% of traces involving DLQ writes
```

### Key Traces to Instrument

| Trace | Key Spans |
|-------|----------|
| Message send (1:1) | WS receive → Cassandra write → Kafka publish → Fan-Out → WS push |
| Message send (group) | Fan-Out member expansion → per-member delivery |
| Offline reconnect | WS connect → Registry update → Sync fetch → Message delivery |
| Presence change | Heartbeat → Redis update → Fan-Out → WS push |
| Media upload | Pre-sign URL → S3 upload → Virus scan → CDN URL generation |

---

## SLI / SLO / SLA Definitions

### SLIs (Service Level Indicators)

| SLI | Measurement |
|-----|------------|
| Message delivery latency | Time from `MessageCreated` event to `MessageDelivered` event for online recipients |
| Message send success rate | % of SEND_MESSAGE requests that result in SENT status |
| Reconnect sync latency | Time from WebSocket connect to all missed messages delivered |
| Search latency | Time from search request to results returned |
| API availability | % of REST API requests returning non-5xx responses |

### SLOs (Service Level Objectives)

| SLO | Target | Error Budget (30 days) |
|-----|--------|----------------------|
| Message delivery latency p99 < 500ms (online → online) | 99.9% of deliveries | 43 minutes |
| Message send success rate | 99.99% | 4.3 minutes |
| API availability | 99.9% | 43 minutes |
| Search latency p99 < 2s | 99.5% | 3.6 hours |
| Presence accuracy | 99% | 7.2 hours |

### Error Budget Consumption Alerts

```
At 50% budget consumed in first 15 days → Warning page to on-call
At 90% budget consumed → Engineering team review required before any risky deployments
At 100% budget consumed → Feature freeze; focus only on reliability improvements
```

---

## Dashboards

### Operations Dashboard (24/7 On-Call)

| Panel | Metric | Visualization |
|-------|--------|--------------|
| Active WebSocket connections | `ws_connections_active` sum | Single stat (with threshold coloring) |
| Message send rate | `message_send_total` rate | Time series |
| Message send latency | `message_send_latency_ms` p50/p99 | Time series |
| Fan-Out consumer lag | `fanout_lag_messages` | Time series with alert threshold |
| Cassandra write latency | p99 per node | Heatmap |
| Redis availability | Per-shard ping latency | Status panel |
| Error rate | All error counters | Time series |
| Online users | `presence_online_users_total` | Gauge |

### Capacity Planning Dashboard (Weekly Review)

| Panel | Metric |
|-------|--------|
| WS connections trend (90-day) | vs. server capacity ceiling |
| Cassandra disk growth | Projected full date |
| Kafka partition lag trend | Consumer throughput vs. message volume |
| Redis memory utilization | Per-cluster |
| Message volume by hour/day | For capacity forecasting |

---

## Alerting Rules

### PagerDuty P1 (Immediate Wake-Up)

```yaml
- alert: MessageDeliveryLatencyP99High
  expr: histogram_quantile(0.99, rate(message_delivery_latency_ms_bucket[5m])) > 500
  for: 5m
  labels: { severity: critical }

- alert: CassandraWriteLatencyHigh
  expr: histogram_quantile(0.99, cassandra_write_latency_ms_bucket) > 50
  for: 2m
  labels: { severity: critical }

- alert: KafkaConsumerLagHigh
  expr: fanout_lag_messages > 50000
  for: 3m
  labels: { severity: critical }

- alert: WebSocketServerConnectionsNearCapacity
  expr: ws_connections_active > 48000
  for: 5m
  labels: { severity: critical }

- alert: DLQMessagesAccumulating
  expr: rate(fanout_dlq_writes_total[1m]) > 10
  for: 2m
  labels: { severity: critical }
```

### PagerDuty P2 (Notify, No Wake-Up)

```yaml
- alert: MessageSendErrorRateHigh
  expr: rate(message_send_error_total[5m]) / rate(message_send_total[5m]) > 0.01
  for: 5m
  labels: { severity: warning }

- alert: RedisLatencyHigh
  expr: histogram_quantile(0.99, redis_command_latency_ms_bucket) > 5
  for: 5m
  labels: { severity: warning }

- alert: PresenceHeartbeatDropped
  expr: rate(presence_heartbeat_rate[5m]) < rate(presence_heartbeat_rate[10m]) * 0.8
  for: 3m
  labels: { severity: warning }

- alert: SearchLatencyHigh
  expr: histogram_quantile(0.99, search_latency_ms_bucket) > 2000
  for: 5m
  labels: { severity: warning }
```

---

## Correlation IDs

Every request in the system carries a `trace_id` that is:
- Generated at the API Gateway for HTTP requests
- Embedded in WebSocket frame headers for WS requests
- Propagated in Kafka message headers
- Logged in every service that touches that request
- Returned in HTTP response headers: `X-Trace-ID: trace-xyz-001`

When a user reports "my message didn't deliver at 10:00 AM", support staff search Kibana by `user_id` AND `timestamp range` AND `event: message_created` → find the `trace_id` → search Jaeger → see exactly where in the pipeline the delivery failed.

---

## Performance Impact of Observability

At 1M messages/second, naive logging would produce:
```
1M log lines/sec × 500 bytes = 500 MB/s → 43 TB/day of logs
```

This is unacceptable. Mitigation:
- Sample 1% of message logs at INFO level (50K/sec instead of 1M/sec)
- Log 100% of errors (rare, acceptable)
- Use structured binary logging format (protobuf instead of JSON) for high-volume paths — 3–5× smaller
- Asynchronous log writing (non-blocking — write to ring buffer, flush on separate thread)
- Log aggregation at WS server (aggregate 1,000 heartbeats into one "batch heartbeat log" per minute)
