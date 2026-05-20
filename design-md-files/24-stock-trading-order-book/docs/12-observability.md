# 12 — Observability: Stock Trading Order Book

## Objective

Define logging, metrics, tracing, and alerting strategy for a latency-sensitive trading system. Observability tooling must not impair matching engine performance.

---

## Observability Principles for Low-Latency Systems

**The matching engine must never block on observability.** All metrics, logs, and traces from the matching engine are:
- Written to a pre-allocated ring buffer (same Disruptor pattern)
- Consumed asynchronously by a separate logging/metrics thread
- Never involve synchronous I/O on the matching thread

Standard logging frameworks (Log4j, SLF4J) with synchronous appenders are **banned** from the matching engine hot path. Async appenders with pre-allocated buffers are used instead.

---

## Metrics (Prometheus + Grafana)

### Matching Engine Metrics

| Metric | Type | Labels | Alert Threshold |
|--------|------|--------|----------------|
| `matching_engine_orders_processed_total` | Counter | symbol | N/A (rate) |
| `matching_engine_trades_executed_total` | Counter | symbol | N/A |
| `matching_engine_order_latency_ns` | Histogram | symbol, order_type | p99 > 500µs |
| `matching_engine_ring_buffer_utilization` | Gauge | symbol | > 50% |
| `matching_engine_order_book_depth` | Gauge | symbol, side | N/A |
| `matching_engine_last_trade_price` | Gauge | symbol | N/A |
| `matching_engine_circuit_breaker_active` | Gauge | symbol | Any = 1 |

### Order Gateway Metrics

| Metric | Type | Labels | Alert Threshold |
|--------|------|--------|----------------|
| `gateway_orders_submitted_total` | Counter | status (accepted/rejected) | N/A |
| `gateway_order_latency_ms` | Histogram | endpoint | p99 > 10ms |
| `gateway_risk_check_latency_ms` | Histogram | result | p99 > 1ms |
| `gateway_active_connections` | Gauge | protocol (REST/FIX/WS) | N/A |
| `gateway_rate_limit_rejections_total` | Counter | participant_id | > 1000/min |

### Risk Engine Metrics

| Metric | Type | Labels | Alert Threshold |
|--------|------|--------|----------------|
| `risk_check_duration_ms` | Histogram | result | p99 > 0.5ms |
| `risk_rejections_total` | Counter | reason | N/A |
| `risk_redis_latency_ms` | Histogram | operation | p99 > 1ms |
| `risk_redis_errors_total` | Counter | error_type | > 0 |

### Kafka Consumer Metrics

| Metric | Type | Labels | Alert Threshold |
|--------|------|--------|----------------|
| `kafka_consumer_lag` | Gauge | group, topic, partition | market-data > 1000; settlement > 10000 |
| `kafka_consumer_poll_duration_ms` | Histogram | group | p99 > 1000ms |
| `kafka_dlq_messages_total` | Counter | topic | > 0 |

---

## SLI / SLO / SLA

| SLI | SLO | Measurement Method |
|-----|-----|--------------------|
| Order submission latency p99 | < 10ms | `gateway_order_latency_ms` histogram |
| Matching latency p99 | < 1ms | `matching_engine_order_latency_ns` histogram |
| Market data fan-out latency p99 | < 5ms | End-to-end trace (gateway to WebSocket delivery) |
| Order submission availability | 99.99% | `gateway_orders_submitted_total` success rate |
| Trade execution accuracy | 100% | Reconciliation against settlement records |
| Order book consistency | 100% | Automated post-market reconciliation |

**Error budget:** 99.99% availability = 52.6 minutes downtime/year. Trading hours are 6.5 hours/day × 252 trading days = 1,638 hours/year. Budget = 9.8 minutes downtime during trading hours.

---

## Dashboards (Grafana)

### Executive Dashboard

- Current trading status per symbol (OPEN / HALTED)
- Orders per second (live)
- Trades per second (live)
- System health summary (red/yellow/green)

### Matching Engine Dashboard

- Ring buffer utilization per symbol (heatmap)
- Order latency histogram (p50/p95/p99/p999)
- Matching throughput (orders/sec per symbol)
- GC pause duration (JVM)
- CPU usage per matching thread

### Market Data Dashboard

- WebSocket client count
- Market data fan-out latency
- Redis Pub/Sub message rate
- Level 1 quote update rate per symbol

### Risk Dashboard

- Risk rejection rate by reason
- Redis buying power reserve utilization
- Risk check latency percentiles

### Kafka Dashboard

- Consumer group lag per topic (all groups)
- DLQ message count (highlighted)
- Kafka broker throughput
- Topic partition leader distribution

---

## Logging Strategy

### Matching Engine (Async, Structured)

The matching engine uses a pre-allocated async log buffer (Chronicle Map or LMAX Chronicle Logger). Log entries are written to the buffer without blocking the matching thread.

```
Log level in matching engine: INFO for trades, WARN for circuit breakers, ERROR for failures
NEVER: DEBUG in production (volume kills throughput)
Format: structured JSON (parseable by ELK)
```

Example log entry (structured):
```json
{
  "ts": "2024-01-15T09:30:00.200123456Z",
  "level": "INFO",
  "service": "matching-engine",
  "symbol": "AAPL",
  "event": "TRADE_EXECUTED",
  "tradeId": "uuid",
  "price": "180.50",
  "qty": 100,
  "seq": 10023457,
  "traceId": "abc123def456"
}
```

### Order Gateway (Standard Async)

- SLF4J + Logback with async appender
- Structured JSON via `logstash-logback-encoder`
- Correlation ID (`traceId`) on every log line
- Log level: INFO in production

### Sensitive Data in Logs

- Never log participant PII (name, address)
- Never log order amounts > $100K inline (log order ID only, amount in separate audit system)
- JWT tokens never logged (even in DEBUG)
- Sanitize all log lines before sending to ELK

---

## Distributed Tracing (OpenTelemetry + Jaeger)

### Trace Scope

Trace spans the critical path: client → Order Gateway → Risk Engine → Ring Buffer publish → [async gap] → Matching Engine → Kafka publish.

**Note:** The async handoff via ring buffer means the trace has a gap. The gateway span ends at ring buffer publish; the matching engine creates a new span linked by `orderId` + `traceId` (propagated via event payload).

```
Trace: order-submission
  └── gateway.receive (0.5ms)
       └── gateway.validate (0.1ms)
       └── risk.check (0.2ms)
       └── ring-buffer.publish (0.05ms)
  [async gap — ring buffer]
  └── matching.process (0.1ms)  ← linked span, not child
       └── matching.match (0.08ms)
       └── kafka.publish (async, not included in latency SLO)
```

### Sampling Rate

- Hot path (matching engine): 0.1% sample — even 0.1% at 100K orders/sec = 100 traces/sec
- Error path: 100% sample — every error traced
- Admin operations: 100% sample

High sampling rate on the hot path would create observability overhead that impairs the latency SLO.

---

## Alerting Strategy

### PagerDuty Alerts (Immediate Wake-Up)

| Condition | Reason |
|-----------|--------|
| Any symbol circuit breaker active | Trading halt — regulators may inquire |
| Matching pod health check fails | Trading halt risk |
| Redis unavailable | Fail-closed, trading halted |
| Settlement DLQ > 0 | Financial data at risk |
| Order submission availability < 99.9% in 5-minute window | SLO breach |
| Matching latency p99 > 5ms for 2 consecutive minutes | Performance degradation |

### Slack Alerts (Non-Urgent)

| Condition | Channel |
|-----------|--------|
| Kafka consumer lag > threshold (non-critical consumers) | #ops-alerts |
| Any DLQ (non-settlement) receives messages | #ops-alerts |
| GC pause > 50ms in any service | #perf-alerts |
| Ring buffer utilization > 50% | #perf-alerts |
| PostgreSQL read replica lag > 30 seconds | #ops-alerts |

---

## Post-Market Reconciliation

Every trading day after market close (4:00 PM):

1. **Order book reconciliation:** All open orders in PostgreSQL `orders` table must match current matching engine in-memory state. Discrepancy → immediate P2 alert.

2. **Trade reconciliation:** Total `TradeExecuted` events in Kafka == total rows in `trades` table. Sequence numbers form continuous series per symbol (no gaps).

3. **Risk reconciliation:** Redis buying power + reserved amounts == PostgreSQL authoritative account balances. Any drift > $1 → alert.

4. **Settlement reconciliation:** Net positions from `trades` == settlement instructions generated. Mismatch → P1 incident.

Automated reconciliation script runs as Kubernetes CronJob at 4:15 PM. Results reported to compliance dashboard.
