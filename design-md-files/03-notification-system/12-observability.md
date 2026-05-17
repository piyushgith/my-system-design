# 12 — Observability: Notification System

---

## Objective

Define the complete observability strategy for the Notification System: logging, metrics, distributed tracing, alerting, dashboards, and SLI/SLO definitions. A notification system without good observability is operationally blind — you cannot tell if OTPs are actually reaching users.

---

## The Three Pillars

| Pillar | Tool | Purpose |
|--------|------|---------|
| **Logs** | ELK Stack (Elasticsearch + Logstash + Kibana) | Debug, audit, PII-masked event trail |
| **Metrics** | Prometheus + Grafana | Real-time system health, SLO tracking |
| **Traces** | OpenTelemetry + Jaeger | End-to-end request path visibility |

---

## Logging Strategy

### Log Levels

| Level | Used For |
|-------|---------|
| ERROR | Unrecoverable failures (provider down, DB write failure, DLQ overflow) |
| WARN | Retriable failures (provider 429, Redis miss, timeout with retry scheduled) |
| INFO | Business events (notification accepted, delivered, preference updated) |
| DEBUG | Technical details (Kafka offset, cache key, template render duration) — disabled in production |

### Correlation ID Propagation

Every request flowing through the system carries a `correlation_id` (also called `trace_id`):

```
Notification API assigns: correlation_id = UUID on every POST /notifications
This value propagates through:
  → Kafka message header: X-Correlation-ID
  → Fanout Service log context
  → Each dispatcher log context
  → Provider API call header (if provider supports it)
  → Delivery attempt log record
```

A single `correlation_id` allows reconstructing the entire notification journey from API submission to provider delivery acknowledgment in Kibana with a single filter.

### Structured Log Format

All services use JSON structured logging (Logstash JSON format):

```json
{
  "timestamp": "2026-05-17T08:00:05.123Z",
  "level": "INFO",
  "service": "email-dispatcher",
  "pod": "email-dispatcher-5b4d7f-k8s",
  "correlation_id": "corr-550e8400",
  "notification_id": "ntf-abc123",
  "attempt_id": "att-xyz789",
  "channel": "EMAIL",
  "provider": "sendgrid",
  "provider_message_id": "SG-abc",
  "status": "DELIVERED",
  "duration_ms": 245,
  "message": "Email dispatched successfully"
}
```

**PII Masking Rules** (enforced by a log filter in the logging framework):
- `email` field: `al***@ex***.com`
- `phone` field: `+91987***10`
- Any field named `otp`, `token`, `password`, `secret`: `[REDACTED]`
- Variables JSONB: keys preserved, values masked based on key name pattern

### Log Retention

| Log Type | Hot Retention | Cold Archive |
|----------|-------------|-------------|
| Application logs | 15 days (Elasticsearch) | 1 year (S3 via Logstash S3 output) |
| Audit logs | 7 years (compliance) | S3 with Object Lock (WORM) |
| Error logs | 30 days (Elasticsearch) | 1 year |

---

## Metrics Strategy

### Key Business Metrics (SLO-Relevant)

| Metric | Type | Description |
|--------|------|-------------|
| `notification_accepted_total` | Counter | Notifications accepted by API |
| `notification_delivered_total` | Counter | Notifications fully delivered (all channels) |
| `notification_failed_total` | Counter | Notifications exhausted all retries |
| `notification_delivery_duration_seconds` | Histogram | End-to-end time from acceptance to delivery |
| `channel_dispatch_duration_seconds` | Histogram | Time from dispatch job created to provider call completed |
| `channel_delivery_success_rate` | Gauge | `delivered / (delivered + failed)` per channel |

### Infrastructure Metrics

| Metric | Type | Alert Threshold |
|--------|------|----------------|
| `kafka_consumer_lag` | Gauge | Alert if lag > 10,000 on dispatch topics |
| `redis_cache_hit_ratio` | Gauge | Alert if < 90% on preference cache |
| `db_connection_pool_wait_ms` | Histogram | Alert if p99 > 100ms |
| `provider_error_rate` | Gauge | Alert if > 5% per provider per 5 min |
| `dlq_topic_depth` | Gauge | Alert if > 1,000 messages |
| `outbox_relay_lag_seconds` | Gauge | Alert if > 5 seconds |

### Provider-Level Metrics

For each external provider (SendGrid, Twilio, FCM):

| Metric | Description |
|--------|-------------|
| `provider_request_total{provider, channel, status}` | Total requests per provider per outcome |
| `provider_request_duration_ms` | Histogram of provider API call latency |
| `provider_rate_limit_hit_total` | Count of 429 responses per provider |
| `provider_circuit_breaker_state` | 0=CLOSED, 1=HALF_OPEN, 2=OPEN |

---

## SLI/SLO/SLA Definitions

### SLI 1: API Availability

```
SLI: (successful responses / total requests) × 100
     where successful = HTTP 2xx + 4xx (valid errors)
     excludes: HTTP 5xx, timeouts
SLO: 99.9% per rolling 30 days
SLA: 99.5% (contractual with downstream teams)
Error Budget: 0.1% of 30 days = 43.2 minutes/month
```

### SLI 2: Transactional Notification Delivery Latency

```
SLI: % of TRANSACTIONAL notifications delivered within 5 seconds of API acceptance
     Measured: notification_accepted_at to delivery_confirmed_at in delivery_attempts
SLO: 99.5% of transactional notifications within 5 seconds
SLA: 99% within 10 seconds
Error Budget: 0.5% = 0.5% of all transactional notifications can exceed 5s
```

### SLI 3: Overall Delivery Success Rate

```
SLI: (notifications_delivered / notifications_accepted) × 100
     Measured over rolling 24 hours
     A notification counts as delivered if at least one channel succeeds
SLO: 99.9% delivery success rate
Exclusions: Hard bounces (invalid addresses) are excluded from numerator and denominator
```

### SLI 4: DLQ Drain SLA

```
SLI: % of DLQ messages reprocessed or acknowledged within 1 hour of entering DLQ
SLO: 99% of DLQ messages handled within 1 hour
Measurement: `dlq_entry_to_resolution_duration_seconds` histogram
```

---

## Distributed Tracing

### OpenTelemetry Setup

All services are instrumented with OpenTelemetry SDK (Java agent for Spring Boot):

```
Trace starts at: Notification API receives POST request
Spans created for:
  - API request validation
  - PostgreSQL write (Outbox insert)
  - Kafka publish
  - Fanout preference lookup (Redis GET)
  - Fanout routing decision
  - Kafka dispatch publish
  - Template render (cache hit/miss)
  - Provider API call (HTTP span)
  - Delivery result Kafka publish
  - ClickHouse write

Spans include:
  - notification_id (in span attributes)
  - channel
  - provider
  - cache_hit (boolean)
  - attempt_number
```

### Trace Sampling Strategy

| Traffic Type | Sampling Rate |
|-------------|--------------|
| CRITICAL priority notifications | 100% (always trace) |
| TRANSACTIONAL notifications | 10% |
| MARKETING notifications | 1% |
| Error paths (any span with error=true) | 100% (always trace errors) |

Full trace sampling at 50K RPS would generate ~5M spans/sec — ClickHouse or Jaeger cannot handle this. Sampling at 1–10% reduces to 500–5,000 spans/sec, manageable.

### Trace Visualization (Jaeger Example)

A single notification trace would show:
```
[Notification API] POST /notifications              45ms
  ↳ [Validation]                                     2ms
  ↳ [PostgreSQL: Insert outbox_event]               12ms
  ↳ [Kafka: Publish notification.requested]          5ms
[Fanout Service] Process notification.requested     15ms
  ↳ [Redis: GET pref:usr-123 (HIT)]                 0.5ms
  ↳ [Kafka: Publish email.dispatch]                  3ms
  ↳ [Kafka: Publish sms.dispatch]                    3ms
[Email Dispatcher] Process email.dispatch          250ms
  ↳ [Redis: GET tpl:order-confirmed:3 (HIT)]        0.3ms
  ↳ [Template: Render]                               1ms
  ↳ [HTTP: SendGrid POST /mail/send]               230ms
  ↳ [Kafka: Publish delivery.attempt.completed]      2ms
```

Total end-to-end: 310ms for email delivery.

---

## Dashboards

### Dashboard 1: Real-Time Notification Health

Panels:
- Notifications accepted/sec (time series)
- Delivery success rate per channel (gauge)
- p50/p95/p99 transactional delivery latency (histogram)
- DLQ depth per channel (bar chart)
- Provider error rate per provider (heatmap)

### Dashboard 2: Kafka Infrastructure

Panels:
- Consumer lag per consumer group (stacked area)
- Producer throughput per topic (time series)
- Under-replicated partitions (alert panel)
- Broker network I/O (time series)

### Dashboard 3: Campaign Monitoring

Panels:
- Active campaigns and progress (table: batch_id, sent/total, ETA)
- Campaign delivery rate (gauge)
- Campaign-specific DLQ depth
- Throttle rate vs actual dispatch rate

### Dashboard 4: Channel Health

For each channel:
- Dispatch success/failure rate
- Provider API latency histogram
- Circuit breaker state
- Rate limit hit count

---

## Alerting Rules

```yaml
# Prometheus alerting rules (abbreviated)

- alert: OTPDeliveryLatencyHigh
  expr: histogram_quantile(0.99, notification_delivery_duration_seconds{category="TRANSACTIONAL"}) > 5
  for: 2m
  severity: P1
  message: "p99 OTP delivery exceeds 5 second SLO"

- alert: EmailDeliveryRateLow
  expr: channel_delivery_success_rate{channel="EMAIL"} < 0.95
  for: 5m
  severity: P2
  message: "Email delivery rate below 95%"

- alert: DLQDepthHigh
  expr: kafka_topic_messages_in_count{topic=~"notification.dlq.*"} > 1000
  for: 0m  # instant alert
  severity: P2

- alert: KafkaConsumerLagCritical
  expr: kafka_consumer_lag{consumer_group="fanout-cg"} > 50000
  for: 3m
  severity: P1
  message: "Fanout consumer lag critical — transactional notifications may be delayed"

- alert: ProviderCircuitBreakerOpen
  expr: provider_circuit_breaker_state{provider="sendgrid"} == 2
  for: 0m  # instant
  severity: P1
  message: "SendGrid circuit breaker OPEN — email delivery halted"
```

---

## Runbook Linkage in Alerts

Every PagerDuty alert includes a link to the relevant runbook (Confluence or GitHub wiki). Runbooks cover:
- Step-by-step diagnosis (which metrics to check, which dashboards to open)
- Mitigation steps (how to scale up dispatchers, how to re-open circuit breaker safely)
- Escalation path (who owns what service)

---

## Interview Discussion Points

- Why is distributed tracing necessary when you already have correlation IDs in logs?
- What is the difference between SLI, SLO, and SLA, and how does the error budget concept work?
- How do you trace a notification that crosses 6 service boundaries without losing the correlation ID?
- Why sample traces at 1% for marketing notifications but 100% for errors and critical notifications?
- How would you investigate an incident where "OTP notifications are slow" with no obvious errors in logs?
- What is the risk of setting `for: 0m` on a Prometheus alert (fires immediately with no buffer)?
