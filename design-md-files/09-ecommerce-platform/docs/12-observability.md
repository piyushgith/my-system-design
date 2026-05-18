# 12 — Observability: E-Commerce Platform

---

## Objective

Define logging, metrics, distributed tracing, alerting, and SLI/SLO strategy to maintain visibility into the health, performance, and correctness of the e-commerce platform.

---

## Three Pillars of Observability

| Pillar | Tool | Purpose |
|---|---|---|
| Logs | ELK (Elasticsearch + Logstash + Kibana) | Event records, debugging, audit |
| Metrics | Prometheus + Grafana | Real-time system health, alerting |
| Traces | Jaeger / Zipkin / OpenTelemetry | Request flow across services |

---

## Logging Strategy

### Log Levels

| Level | When to Use |
|---|---|
| ERROR | Exception caught, external service failure, data integrity issue |
| WARN | Retry attempt, degraded mode, near-threshold warning |
| INFO | Business event (order placed, payment succeeded), startup events |
| DEBUG | Internal state, request/response bodies (disabled in prod) |

### Structured JSON Logging

All logs as JSON — machine parseable, Kibana-friendly:

```json
{
  "timestamp": "2026-01-15T10:23:45.123Z",
  "level": "INFO",
  "service": "order-service",
  "traceId": "abc123",
  "spanId": "def456",
  "userId": "usr_789",
  "orderId": "ord_456",
  "event": "ORDER_PLACED",
  "amount": 2499.00,
  "currency": "INR",
  "message": "Order placed successfully"
}
```

### What to Log (and Not Log)

**Always log**:
- Order state transitions (placed, confirmed, shipped, delivered, cancelled)
- Payment events (initiated, succeeded, failed, refunded)
- Authentication events (login success, failure, MFA)
- Inventory changes (reserved, released, updated)
- Admin actions

**Never log**:
- Passwords (even hashed)
- Full card numbers
- CVV
- Raw payment tokens
- Full SSN or tax IDs

**Mask in logs**:
- Card number → `****-****-****-4242`
- Email → `p***@gmail.com`
- Phone → `+91-****-1234`

### Correlation ID / Trace ID

Every request tagged with:
- `traceId`: UUID generated at API gateway, propagated through all services
- `spanId`: per-service span within trace
- `correlationId`: business-level ID (orderId, paymentId)

Propagated via HTTP headers: `X-Trace-ID`, `X-Correlation-ID`.

---

## Metrics Strategy

### Business Metrics (Most Important)

| Metric | Alert Threshold | Severity |
|---|---|---|
| Orders per minute | < 50% of baseline (15min avg) | P1 |
| Payment success rate | < 95% | P1 |
| Checkout conversion rate | < 80% of baseline | P2 |
| Cart abandonment spike | > 150% of baseline | P2 |
| Search result latency | p99 > 2s | P2 |
| Fraud rate | > 0.5% of transactions | P1 |

### Technical Metrics

**Order Service**:
- `order_placed_total` (counter)
- `order_processing_duration_seconds` (histogram)
- `order_failure_total` (counter, labeled by reason)

**Inventory Service**:
- `inventory_reservation_total`
- `inventory_oversell_total` (should always be 0)
- `inventory_redis_miss_total`

**Payment Service**:
- `payment_initiated_total`
- `payment_success_total`
- `payment_failure_total` (labeled by error code)
- `payment_gateway_latency_seconds` (histogram)

**Infrastructure**:
- `jvm_heap_used_bytes`
- `db_connection_pool_active`
- `redis_connected_clients`
- `kafka_consumer_lag`
- `http_request_duration_seconds` (histogram, labeled by endpoint)

### RED Method (per service)

For each service track:
- **R**ate: requests per second
- **E**rrors: error rate (4xx, 5xx)
- **D**uration: request latency (p50, p95, p99)

### USE Method (per resource)

For each infrastructure component:
- **U**tilization: CPU, memory %
- **S**aturation: queue depth, wait time
- **E**rrors: error count

---

## SLI / SLO / SLA

### Service Level Indicators (SLI)

Measured values:
- Order API availability (% of requests succeeding)
- Payment API latency (p99 response time)
- Search latency (p95 response time)
- Inventory accuracy (% of purchases where inventory was correctly reserved)

### Service Level Objectives (SLO)

Targets (internal, not customer-facing):
| SLI | SLO Target |
|---|---|
| Order API availability | 99.9% (< 8.7 hours downtime/year) |
| Payment API availability | 99.99% (< 52 minutes downtime/year) |
| Checkout latency p99 | < 2 seconds |
| Search latency p95 | < 500ms |
| Inventory accuracy | 99.999% |

### Error Budget

- 99.9% SLO = 0.1% error budget = 43.8 min/month
- Alert when error budget < 20% remaining in rolling 30-day window
- Freeze non-critical releases when error budget < 10%

### SLA (Customer-facing)

- Stated availability: 99.9%
- Refund/compensation policy if SLA breached

---

## Distributed Tracing

### OpenTelemetry Setup

- Instrument all services with OpenTelemetry Java SDK
- Auto-instrumentation via Java agent (covers HTTP, JDBC, Redis, Kafka)
- Export traces to Jaeger or Tempo

### Critical Traces to Capture

1. **Checkout flow**: `cart-service → inventory-service → payment-service → order-service`
   - Shows full latency breakdown
   - Identifies bottleneck (which service is slow)

2. **Order confirmation**: `order-service → kafka → notification-service`
   - Shows end-to-end delay for confirmation email

3. **Search**: `API gateway → search-service → Elasticsearch`
   - ES query time vs application overhead

### Trace Sampling

- 100% sampling for: errors, payment flows, latency > 1s
- 1% sampling for: successful catalog reads (too high volume)
- 10% sampling for: search queries

---

## Dashboards

### Operations Dashboard (always visible)

```
┌──────────────────────────────────────────────────────────┐
│  Orders/min  │  Payment Success %  │  P99 Checkout Latency│
│    1,234      │       98.7%         │        1.2s          │
├──────────────┼─────────────────────┼──────────────────────┤
│  Cart Active  │  Inventory Errors  │  Kafka Consumer Lag  │
│    45,231     │        0           │        234           │
├──────────────┴─────────────────────┴──────────────────────┤
│  [Order Rate Graph]  [Error Rate Graph]  [Latency P99]   │
└──────────────────────────────────────────────────────────┘
```

### Business Dashboard (daily review)

- Revenue by hour / day / week
- Top products by order count
- Conversion funnel: session → product view → add to cart → checkout → order
- Geographic distribution of orders
- Payment method breakdown

### Flash Sale Dashboard (event-specific)

- Created before flash sale; pre-loaded with relevant metrics
- Real-time: orders/sec, inventory remaining, Redis DECR rate, payment success rate
- Visible to engineering + product during sale

---

## Alerting

### Severity Levels

| Severity | Response Time | Examples |
|---|---|---|
| P1 (Critical) | < 5 minutes | Payment API down, order placement failing |
| P2 (High) | < 30 minutes | Payment success rate < 95%, search slow |
| P3 (Medium) | < 4 hours | High cart abandonment, Kafka lag growing |
| P4 (Low) | Next business day | Minor latency regression, non-critical service warning |

### Alert Routing

- P1 → PagerDuty → on-call engineer (immediate phone call)
- P2 → PagerDuty → Slack #alerts (no phone)
- P3 → Slack #warnings
- P4 → Jira ticket auto-created

### Runbook Links in Alerts

Every alert includes:
- What is alerting (metric name, current value, threshold)
- Link to runbook (step-by-step diagnosis)
- Dashboard link

---

## Audit Observability

Business audit trail (separate from operational logs):

| Event | Data Captured |
|---|---|
| Order placed | userId, orderId, items, amounts, IP |
| Payment captured | orderId, paymentId, amount, method |
| Refund issued | orderId, refundId, amount, reason, approvedBy |
| Price change | productId, oldPrice, newPrice, changedBy |
| Inventory adjustment | productId, delta, reason, adjustedBy |
| Admin login | adminId, IP, timestamp, MFA used |

Audit logs: append-only, stored in S3 with CloudTrail. Retention: 7 years (compliance).

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Structured JSON logs | Machine parseable, searchable | Larger log volume vs plain text |
| 100% trace sampling for errors | Full visibility on failures | Storage cost; need sampling for high-volume success paths |
| Business metrics as primary SLI | Measures what users care about | Harder to instrument than infrastructure metrics |
| Separate audit log store | Compliance, tamper-proof | Extra infrastructure |

---

## Interview Discussion Points

- **"What's your SLO for the payment API?"** → 99.99% availability, p99 < 2s; discuss error budget burning
- **"How do you debug a slow checkout?"** → Distributed trace: find which service/DB query is slow; RED metrics per service
- **"How do you know if orders are dropping?"** → Alert on orders/min < 50% of baseline; synthetic orders (health check orders) running every minute
- **"How do you ensure logs don't contain card data?"** → Custom Logback filter masks sensitive fields; automated PII scanning in log pipeline
- **"What's your on-call rotation?"** → P1 alerts → PagerDuty, 12-hour shifts, runbook-first culture
