# 12 — Observability: URL Shortener

---

## Objective

Define the logging, metrics, distributed tracing, alerting, and SLI/SLO strategy that gives full production visibility into the URL shortener system.

---

## Observability Pillars

```
┌─────────────────────────────────────────────────────────┐
│                  Observability Stack                     │
│                                                         │
│  Logs          Metrics          Traces                  │
│  (ELK/Loki)   (Prometheus)     (Jaeger/Tempo)           │
│       ↓              ↓               ↓                  │
│            Grafana Dashboards                            │
│                    ↓                                    │
│           Alertmanager → PagerDuty / Slack               │
└─────────────────────────────────────────────────────────┘
```

---

## Logging Strategy

### Log Format (Structured JSON)

All logs are structured JSON — never free-text. Machine-parseable. Example:

```json
{
  "timestamp": "2024-01-15T10:30:00.123Z",
  "level": "INFO",
  "service": "url-shortener",
  "module": "redirect",
  "traceId": "abc123def456",
  "spanId": "789xyz",
  "shortCode": "aB3xYz",
  "event": "URL_REDIRECTED",
  "cacheHit": true,
  "redirectType": "DIRECT",
  "latencyMs": 3,
  "userId": null,
  "country": "US",
  "device": "MOBILE"
}
```

### Log Levels and Usage

| Level | When to Use | Examples |
|---|---|---|
| ERROR | Unrecoverable failure requiring operator attention | DB connection failed, Kafka publish exhausted retries |
| WARN | Recoverable issue that should be investigated | Cache miss rate > 20%, slow DB query > 100ms |
| INFO | Normal business events | URL created, URL redirected, user authenticated |
| DEBUG | Detailed internal flow (disabled in production) | Cache key computed, SQL query executed |

**Rule**: Never log in a tight loop (e.g., every redirect). Log at the boundary of operations (request in/out). Use metrics for high-frequency events.

### Log Pipeline

```
Application → Logback → Fluentd/Fluent Bit → Elasticsearch / Loki
                                         ↓
                                    Kibana / Grafana Explore
```

**Fluentd**: Runs as DaemonSet in Kubernetes; tails container logs; ships to Elasticsearch.

**Retention**:
- Hot (searchable): 7 days in Elasticsearch
- Warm (compressed): 30 days in S3
- Cold (archived): 1 year in S3 Glacier (compliance)

### Correlation ID Propagation

Every request gets a `traceId` at the entry point (API gateway or ALB). Propagated via:
- HTTP request: `X-Trace-Id: <uuid>` header
- Kafka message: `traceId` field in event payload
- MDC (Mapped Diagnostic Context) in Spring: automatically included in all log lines within a request thread
- Grafana Tempo: click trace ID in Grafana → see full distributed trace

---

## Metrics Design

### Technology: Prometheus + Grafana

- Spring Boot Actuator exposes metrics at `/actuator/prometheus`
- Prometheus scrapes every 15 seconds
- Grafana visualizes and powers alerting

### Key Application Metrics

#### Redirect Service Metrics

```
# Redirect request count by result type
url_redirect_total{result="cache_hit|cache_miss|not_found|expired|blocked", country, device}

# Redirect latency histogram (track p50, p95, p99)
url_redirect_duration_seconds{cache_hit="true|false"}

# Cache layer hit rates
url_cache_hit_total{layer="l1_caffeine|l2_redis|l3_postgres"}

# Redirect result breakdown
url_redirect_result_total{result="success|expired|blocked|not_found"}
```

#### URL Management Metrics

```
# URL creation rate
url_created_total{tier="free|pro|enterprise", has_alias="true|false"}

# URL creation latency
url_creation_duration_seconds

# Short code collision rate
url_shortcode_collision_total

# Active URLs gauge
url_active_count
```

#### Analytics Pipeline Metrics

```
# Kafka consumer lag
kafka_consumer_lag{group, topic, partition}

# Click events processed rate
click_events_processed_total{result="success|dlq"}

# ClickHouse insert batch latency
clickhouse_insert_duration_seconds

# DLQ size
kafka_dlq_size{topic}
```

#### Infrastructure Metrics

```
# Redis
redis_hit_rate
redis_memory_used_bytes
redis_connected_clients
redis_commands_per_second

# PostgreSQL
postgres_connections_active
postgres_query_duration_seconds{query_type}
postgres_replication_lag_seconds

# JVM
jvm_memory_used_bytes{area}
jvm_gc_pause_seconds
jvm_threads_live
```

---

## Grafana Dashboards

### Dashboard 1: Redirect SLA Overview

| Panel | Query | Threshold |
|---|---|---|
| Redirect p99 latency | `histogram_quantile(0.99, url_redirect_duration_seconds)` | Alert > 100ms |
| Redirect RPS | `rate(url_redirect_total[5m])` | Info |
| Cache hit rate | `rate(url_cache_hit_total{layer="l2_redis"}[5m]) / rate(url_redirect_total[5m])` | Alert < 90% |
| Error rate | `rate(url_redirect_result_total{result!="success"}[5m])` | Alert > 1% |
| Redirect by country | Geo map panel | Visibility |

### Dashboard 2: Analytics Pipeline Health

| Panel | Metric | Threshold |
|---|---|---|
| Kafka consumer lag | `kafka_consumer_lag{group="analytics-click-ingester"}` | Alert > 100K |
| DLQ size | `kafka_dlq_size{topic="url.redirected.dlq"}` | Alert > 100 |
| Click events/sec | `rate(click_events_processed_total[1m])` | Info |
| ClickHouse insert p99 | `histogram_quantile(0.99, clickhouse_insert_duration_seconds)` | Alert > 10s |

### Dashboard 3: Database Health

| Panel | Metric | Threshold |
|---|---|---|
| Active connections | `postgres_connections_active` | Alert > 90 (max 100) |
| Slow queries | `postgres_query_duration_seconds{quantile="0.99"}` | Alert > 50ms |
| Replication lag | `postgres_replication_lag_seconds` | Alert > 1s |
| Redis memory % | `redis_memory_used_bytes / redis_maxmemory_bytes` | Alert > 80% |

---

## Distributed Tracing

### Technology: OpenTelemetry + Jaeger / Grafana Tempo

**Auto-instrumented in Spring Boot** via `spring-boot-starter-opentelemetry`:
- HTTP requests (all controllers)
- Database queries (JDBC/JPA)
- Redis calls
- Kafka produce/consume
- External HTTP calls (Safe Browsing API)

### Trace Example: Cache Miss Redirect

```
Trace: GET /aB3xYz (total: 18ms)
├── RedirectController.handleRedirect (18ms)
│   ├── RedisCache.get (1ms) — MISS
│   ├── PostgresReadReplica.findByShortCode (12ms) — HIT
│   ├── RedisCache.set (1ms) — WARM
│   ├── KafkaProducer.sendAsync (0ms fire-and-forget)
│   └── Response: 302 (4ms)
```

**Sampling strategy**:
- 100% sampling for errors and > 200ms requests
- 1% sampling for successful fast requests (< 50ms)
- Dynamic sampling: increase sampling rate when error rate rises

---

## Alerting Strategy

### Alert Tiers

| Tier | Severity | Response Time | Channel |
|---|---|---|---|
| P0 - Critical | Service down | < 5 min | PagerDuty (wake up on-call) |
| P1 - High | SLA violation | < 30 min | PagerDuty (business hours) |
| P2 - Medium | Degraded performance | < 2 hours | Slack #alerts |
| P3 - Low | Warning / trend | < 24 hours | Slack #monitoring |

### Alert Rules

```yaml
# P0: Redirect service down
alert: RedirectServiceDown
expr: up{job="url-shortener-redirect"} == 0
for: 1m
labels: { severity: P0 }
annotations:
  summary: "Redirect service is completely down"
  runbook: "https://wiki/runbooks/redirect-down"

# P0: Error rate spike
alert: RedirectErrorRateHigh
expr: rate(url_redirect_result_total{result!="success"}[5m]) / rate(url_redirect_total[5m]) > 0.05
for: 2m
labels: { severity: P0 }
annotations:
  summary: "Redirect error rate > 5% for 2 minutes"

# P1: Latency SLA breach
alert: RedirectLatencyHigh
expr: histogram_quantile(0.99, url_redirect_duration_seconds) > 0.1
for: 5m
labels: { severity: P1 }
annotations:
  summary: "Redirect p99 latency > 100ms for 5 minutes"

# P1: Cache hit rate degraded
alert: CacheHitRateLow
expr: rate(url_cache_hit_total{layer="l2_redis"}[5m]) / rate(url_redirect_total[5m]) < 0.85
for: 5m
labels: { severity: P1 }

# P1: Cache eviction consumer lagging
alert: CacheEvictionLag
expr: kafka_consumer_lag{group="cache-eviction-handler"} > 10000
for: 2m
labels: { severity: P1 }
annotations:
  summary: "Deleted URLs may still be redirecting"

# P2: Kafka analytics lag
alert: AnalyticsConsumerLag
expr: kafka_consumer_lag{group="analytics-click-ingester"} > 100000
for: 5m
labels: { severity: P2 }

# P2: PostgreSQL replication lag
alert: PostgresReplicationLag
expr: postgres_replication_lag_seconds > 5
for: 2m
labels: { severity: P2 }

# P3: DLQ non-empty
alert: DLQMessagesFound
expr: kafka_dlq_size{topic=~".*dlq"} > 0
for: 1m
labels: { severity: P3 }
```

---

## SLI / SLO / SLA Definition

### SLI (Service Level Indicators)

| SLI | Definition | Measurement |
|---|---|---|
| Redirect availability | % of valid redirect requests that succeed | `success / total` |
| Redirect latency | p99 latency of redirect requests | Histogram percentile |
| URL creation success rate | % of URL creation requests that succeed | `2xx / total` |
| Analytics freshness | Time between redirect and analytics update | Kafka consumer lag |

### SLO (Service Level Objectives)

| SLO | Target | Measurement Window |
|---|---|---|
| Redirect availability | ≥ 99.99% | 30-day rolling |
| Redirect p99 latency | ≤ 100ms | 1-hour rolling |
| URL creation availability | ≥ 99.9% | 30-day rolling |
| Analytics freshness | ≤ 60 seconds | 30-day average |

### Error Budget

- 99.99% availability over 30 days = 4.38 minutes of allowed downtime
- Error budget tracking: if consumed > 50% in first 15 days → freeze risky deployments
- Error budget reporting: weekly ops review

### SLA (Service Level Agreement — External)

What customers are contractually promised:
- Free tier: 99.9% availability (no contractual guarantee)
- Pro tier: 99.95% availability
- Enterprise tier: 99.99% with credits for breach

---

## Health Check Design

### Endpoints

```
GET /health/live
→ Returns 200 if JVM is alive (not deadlocked, not OOM)
→ Kubernetes liveness probe: restart on failure

GET /health/ready
→ Returns 200 only if ALL dependencies healthy:
  - PostgreSQL write connection: OK
  - Redis connection: OK
  - Kafka producer: OK
→ Kubernetes readiness probe: remove from LB on failure

GET /health/startup
→ Returns 200 once initial startup (cache warm-up, schema migration) complete
→ Kubernetes startup probe: give app time to initialize before liveness kicks in
```

### Dependency Health Checks

| Dependency | Healthy Check | Unhealthy Action |
|---|---|---|
| PostgreSQL primary | Execute `SELECT 1` | Mark app not-ready |
| Redis | PING | Mark app not-ready |
| Kafka | Check producer connected | Mark app not-ready |
| PostgreSQL read replica | Execute `SELECT 1` | Log warning, serve from primary |
| ClickHouse | TCP connect check | Degrade analytics; don't affect redirect |

---

## Interview Discussion Points

- **How do you debug a latency spike at 3am?** Check Grafana for the exact timeline: first identify which metric spiked (cache hit rate? DB latency?). Narrow to Jaeger traces with > 200ms. Check if it correlates with a deployment, traffic spike, or GC pause
- **What's your on-call runbook for redirect service down?** (1) Check pod status in k9s/kubectl. (2) Check ALB target health. (3) Check Redis connectivity. (4) Check DB connectivity. (5) Check recent deployment. (6) Rollback if needed. Each step has a 2-minute timeout
- **How do you prove you're meeting your 99.99% SLA?** Prometheus recording rule computes availability over 30-day window: `sum(rate(url_redirect_result_total{result="success"}[30d])) / sum(rate(url_redirect_total[30d]))`. Grafana burns down the error budget in real-time
- **What observability is missing in V1 that you'd add in V2?** Real user monitoring (RUM) — measure latency from browser perspective, not just origin. Synthetic monitoring — proactively test short URLs from multiple regions every minute. Customer-visible status page (Statuspage.io integration)
