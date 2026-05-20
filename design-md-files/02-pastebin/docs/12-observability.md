# 12 — Observability: Pastebin / Code Sharing Platform

---

## Objective

Design a complete observability strategy covering structured logging, distributed tracing, metrics, dashboards, alerting, and SLI/SLO/SLA definitions. Build a system where "unknown unknowns" surface quickly.

---

## Observability Pillars

```
Logs    → WHAT happened (events, errors, audit trail)
Metrics → HOW MUCH / HOW FAST (counters, gauges, histograms)
Traces  → WHERE time was spent (latency breakdown across services)

All three linked by: Correlation ID / Trace ID
```

---

## Correlation IDs and Request Tracing

Every inbound request receives a globally unique **Trace ID** injected at the API Gateway:

```http
Request in:
  X-Request-ID: 550e8400-e29b-41d4-a716-446655440000  (from client, or generated)

Response out:
  X-Trace-Id: 550e8400-e29b-41d4-a716-446655440000
  X-Correlation-Id: 550e8400-e29b-41d4-a716-446655440000
```

**Propagation:**
- HTTP request to downstream services: `traceparent` header (W3C Trace Context)
- Kafka messages: `trace-id` field in message envelope
- Logs: MDC (Mapped Diagnostic Context) in every log line
- DB queries: `application_name` set to trace ID for PostgreSQL `pg_stat_activity` correlation

---

## Structured Logging

### Log Format (JSON)

Every log line is structured JSON — never plain text in production.

```json
{
  "timestamp": "2026-05-17T12:00:00.123Z",
  "level": "INFO",
  "service": "pastebin-app",
  "instance": "pastebin-app-7d8f9c-k8s-pod",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "spanId": "a3ce929d0e0e4736",
  "userId": "usr_abc123",
  "pasteKey": "abc123",
  "action": "paste.created",
  "durationMs": 145,
  "contentSize": 4096,
  "language": "java",
  "message": "Paste created successfully"
}
```

### Log Levels and When to Use Them

| Level | Use Case | Example |
|-------|---------|---------|
| ERROR | Unrecoverable failures, needs immediate attention | S3 upload failed after 5 retries |
| WARN | Degraded behavior, unexpected but recoverable | Cache miss rate above threshold, Redis retry |
| INFO | Normal business events (keep sparse) | Paste created, user logged in |
| DEBUG | Detailed flow for troubleshooting (disabled in prod) | SQL query parameters, cache key computed |
| TRACE | Extremely detailed (only during incident investigation) | Every byte read from S3 |

**Do NOT log:**
- Raw paste content (even for debugging) — privacy
- Passwords, API keys, tokens — security
- PII without masking — compliance
- Stack traces at INFO level — noise

### Log Retention

| Log Type | Retention | Storage |
|---------|----------|---------|
| Application logs | 30 days | Elasticsearch / OpenSearch |
| Access logs (CDN) | 90 days | S3 + Athena for query |
| Audit logs | 7 years | S3 Glacier (append-only) |
| Security events | 1 year | Elasticsearch |

---

## Metrics (Prometheus + Grafana)

### Business Metrics (Golden Signals)

**Traffic:**
```
pastebin_paste_created_total{language, access_level}
pastebin_paste_viewed_total{cache_layer="cdn|redis|db"}
pastebin_paste_deleted_total{reason="user|expired|abuse"}
pastebin_api_requests_total{method, endpoint, status_code}
```

**Latency:**
```
pastebin_paste_create_duration_seconds{quantile="0.5|0.9|0.99"}
pastebin_paste_read_duration_seconds{quantile="0.5|0.9|0.99", cache_layer}
pastebin_s3_upload_duration_seconds{quantile="0.5|0.99"}
pastebin_db_query_duration_seconds{quantile="0.5|0.99", query_type}
```

**Error rates:**
```
pastebin_api_errors_total{endpoint, error_type}
pastebin_s3_errors_total{operation, error_code}
pastebin_db_errors_total{query_type, error_type}
pastebin_kafka_producer_errors_total{topic}
pastebin_kafka_consumer_errors_total{topic, consumer_group}
```

**Saturation:**
```
pastebin_db_connections_active
pastebin_db_connections_waiting
pastebin_redis_memory_used_bytes
pastebin_redis_memory_max_bytes
pastebin_kafka_consumer_lag{topic, consumer_group, partition}
pastebin_jvm_heap_used_bytes
pastebin_jvm_threads_live
```

### Infrastructure Metrics

```
# PostgreSQL (via pg_exporter)
pg_stat_database_tup_fetched{datname="pastebin"}
pg_stat_database_tup_inserted{datname="pastebin"}
pg_replication_lag_seconds

# Redis (via redis_exporter)
redis_connected_clients
redis_keyspace_hits_total
redis_keyspace_misses_total
redis_evicted_keys_total

# S3 (via CloudWatch → Prometheus)
s3_bucket_size_bytes
s3_number_of_objects
s3_request_errors_total

# Kafka (via JMX Exporter)
kafka_consumer_group_lag{group, topic, partition}
kafka_server_broker_bytes_in_rate
kafka_server_broker_bytes_out_rate
```

---

## Distributed Tracing (OpenTelemetry)

### Instrumentation

Spring Boot auto-instrumented via `spring-boot-starter-actuator` + `opentelemetry-spring-boot-starter`:

- HTTP requests → spans
- JDBC queries → spans (with sanitized SQL)
- Redis commands → spans
- Kafka produce/consume → spans with message attributes
- S3 SDK calls → spans

### Trace Example: Paste Creation

```
Trace: Create Paste (550e8400)
├── HTTP POST /api/v1/pastes [145ms]
│   ├── Rate limit check (Redis) [1ms]
│   ├── Input validation [0.5ms]
│   ├── ShortKey generation + collision check (Redis SET NX) [2ms]
│   ├── S3 PutObject [120ms]  ← Slowest step
│   ├── PostgreSQL INSERT pastes [5ms]
│   ├── PostgreSQL INSERT outbox_events [1ms]
│   └── COMMIT [3ms]
```

**Alerting from traces:**
- p99 trace duration > 500ms → alert
- S3 span p99 > 300ms → CDN/S3 degradation alert
- DB span p99 > 50ms → DB query regression alert

### Trace Sampling

| Traffic Level | Sampling Rate |
|--------------|--------------|
| Errors (5xx) | 100% — always trace errors |
| Slow requests (> 1s) | 100% — always trace slow |
| Normal traffic | 5% random sampling |
| Debug mode (incident) | 100% temporary override |

---

## SLI / SLO / SLA Definitions

### SLI (Service Level Indicators)

| SLI | Measurement |
|-----|------------|
| Read availability | % of `GET /pastes/*` requests with HTTP 2xx or 4xx (non-5xx) |
| Write availability | % of `POST /pastes` requests with HTTP 201 or 4xx |
| Read latency | p99 latency of paste read requests (successful) |
| Write latency | p99 latency of paste creation requests (successful) |
| Cache hit ratio | % of reads served from CDN or Redis (not origin DB) |
| Cleanup timeliness | % of expirations processed within 5 minutes of expiry |

### SLO (Service Level Objectives)

| SLO | Target | Measurement Window |
|-----|--------|--------------------|
| Read availability | 99.9% | 30-day rolling |
| Write availability | 99.5% | 30-day rolling |
| Read latency p99 | < 200ms | 1-hour rolling |
| Write latency p99 | < 500ms | 1-hour rolling |
| Cleanup timeliness | 99% within 5 minutes | 24-hour rolling |
| Cache hit ratio | > 80% | 1-hour rolling |

**Error budget (99.9% read availability, 30 days):**
- Total minutes: 43,200
- Allowed downtime: 43.2 minutes/month
- Used up by: planned maintenance, incidents, deploys

### SLA (Service Level Agreement)

| Tier | Availability Commitment | Support SLA |
|------|------------------------|-------------|
| Free tier (anonymous) | 99% (no formal SLA) | Best effort |
| Free registered user | 99.5% | 48-hour response |
| API users (paid) | 99.9% | 4-hour response |
| Enterprise | 99.95% | 1-hour response + dedicated support |

---

## Dashboards

### Dashboard 1: System Health Overview

```
Row 1: Traffic
  - Paste creation rate (RPS)
  - Paste read rate (RPS) [CDN vs Redis vs DB]
  - API error rate (%)

Row 2: Latency
  - Read latency p50/p95/p99 (time series)
  - Write latency p50/p95/p99 (time series)
  - S3 upload latency p99

Row 3: Saturation
  - DB connections active/waiting
  - Redis memory used %
  - JVM heap used %
  - Kafka consumer lag (per group)

Row 4: Errors
  - 5xx error rate by endpoint
  - S3 error rate
  - DB error rate
```

### Dashboard 2: Business Metrics

```
- Pastes created per day (trend line, 30 days)
- Pastes created by language (pie chart)
- Pastes created by access level (public/unlisted/private)
- Top 10 most viewed pastes (current day)
- Abuse flag rate (% of new pastes flagged)
- Expiry distribution (how long users set expiries)
```

### Dashboard 3: Cache Performance

```
- CDN hit rate (%) — time series
- Redis hit rate (%) — time series
- Redis eviction rate (keys/minute)
- Cache invalidation latency (histogram)
- CDN invalidation success rate
```

### Dashboard 4: Cleanup Health

```
- Expiry schedule backlog (pending count)
- Expiry processing rate (per minute)
- S3 cleanup success rate
- S3 cleanup DLQ count
- CDN invalidation success rate
- Total S3 storage used (trend, cost projection)
```

---

## Alerting Strategy

### Alert Severity Levels

| Severity | Response | Example |
|---------|---------|---------|
| P1 (Critical) | Page on-call immediately | Read availability < 99% for 5 min |
| P2 (High) | Alert on-call, 30 min response | Write availability < 99% for 10 min |
| P3 (Medium) | Slack notification, next business day | Cache hit ratio < 70% for 1 hour |
| P4 (Low) | Ticket, best effort | S3 storage growth 2x above baseline |

### Specific Alert Rules

```yaml
- alert: PasteReadAvailabilityLow
  expr: rate(pastebin_api_requests_total{status_code=~"5.."}[5m]) /
        rate(pastebin_api_requests_total[5m]) > 0.01
  for: 5m
  severity: P1
  message: "Read error rate exceeds 1% — potential outage"

- alert: PasteCreateLatencyHigh
  expr: histogram_quantile(0.99, pastebin_paste_create_duration_seconds) > 1.0
  for: 10m
  severity: P2
  message: "Paste creation p99 latency exceeds 1 second"

- alert: KafkaConsumerLagHigh
  expr: kafka_consumer_group_lag{group="cleanup-expiry-consumer-group"} > 10000
  for: 15m
  severity: P2
  message: "Cleanup consumer lag high — pastes may not expire on time"

- alert: RedisCacheHitRateLow
  expr: rate(redis_keyspace_hits_total[5m]) /
        (rate(redis_keyspace_hits_total[5m]) + rate(redis_keyspace_misses_total[5m])) < 0.7
  for: 10m
  severity: P3
  message: "Redis cache hit rate below 70% — investigate cache"

- alert: CleanupDLQNonEmpty
  expr: kafka_consumer_group_lag{group="dlq-consumer-group", topic="paste.cleanup.dlq"} > 0
  for: 1m
  severity: P2
  message: "S3 cleanup DLQ has messages — manual investigation required"

- alert: DbConnectionPoolExhausted
  expr: pastebin_db_connections_waiting > 5
  for: 5m
  severity: P1
  message: "DB connection pool waiting — risk of request failures"
```

---

## On-Call Runbooks

Each alert has a corresponding runbook:

**Runbook: PasteReadAvailabilityLow**
```
1. Check if PostgreSQL primary is alive (DB health check)
2. Check Redis connectivity (redis-cli ping)
3. Check if recent deploy correlates (git log, deployment timestamps)
4. Check S3 status (AWS Service Health Dashboard)
5. If deploy regression: kubectl rollout undo deployment/pastebin-app
6. If DB down: trigger Patroni failover
7. If Redis down: check Redis Cluster status (redis-cli cluster info)
```

---

## Observability Stack

| Tool | Purpose |
|------|---------|
| Prometheus | Metrics collection and storage |
| Grafana | Dashboards and alerting |
| OpenTelemetry | Trace instrumentation (auto + manual) |
| Jaeger / Tempo | Trace storage and query |
| Elasticsearch / OpenSearch | Log storage and search |
| Logstash / Fluentd | Log shipping from containers |
| Kibana | Log analysis and dashboards |
| PagerDuty | On-call alerting and escalation |
| Slack | Team notifications (P2, P3, P4) |

---

## Interview Discussion Points

- What is the difference between SLI, SLO, and SLA? Give examples for Pastebin.
- Why is p99 latency a better alerting signal than average latency?
- How do you correlate a slow user request across 3 layers: HTTP → Redis → S3?
- Why should audit logs be append-only and never in the same DB as application data?
- What is "cardinality" in metrics and why is it a problem? (e.g., using pasteId as a label would create millions of unique metric series)
- How does distributed tracing help you find that S3 upload is the bottleneck without looking at any code?
- What would be your first 3 things to check when read latency p99 spikes from 100ms to 1500ms?
