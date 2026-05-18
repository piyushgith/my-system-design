# 12 — Observability: Ride-Sharing Platform

## Objective
Define the complete observability strategy for a ride-sharing platform — covering metrics, logging, distributed tracing, alerting, SLIs/SLOs, and dashboards. A ride-sharing system has unique observability demands: real-time geospatial state, multi-service trip lifecycle, and hard latency SLOs (matching must complete in < 3s).

---

## Observability Pillars

| Pillar | Tool | Purpose |
|--------|------|---------|
| **Metrics** | Prometheus + Grafana | Service health, business KPIs, infrastructure |
| **Logs** | ELK Stack (Elasticsearch + Logstash + Kibana) | Structured log aggregation, debugging |
| **Distributed Tracing** | Jaeger (OpenTelemetry) | Cross-service request tracing, latency attribution |
| **Alerting** | Alertmanager + PagerDuty | On-call notifications, SLO breach |
| **Business Metrics** | Kafka → Flink → ClickHouse → Grafana | Real-time business KPIs |

---

## Key Business Metrics (SLI Candidates)

| Metric | Description | Target |
|--------|-------------|--------|
| **Match Rate** | % of ride requests successfully matched to a driver | > 90% |
| **Match Latency P99** | Time from ride request to driver match confirmed | < 3 seconds |
| **Driver Acceptance Rate** | % of matched requests driver accepts (not cancels) | > 85% |
| **Trip Completion Rate** | % of matched trips that complete successfully | > 97% |
| **ETA Accuracy** | % of trips where actual pickup time within ±2 min of ETA | > 80% |
| **Payment Success Rate** | % of completed trips where payment processes successfully | > 99.5% |
| **Location Update Lag** | P99 time from driver GPS event to it being queryable in Redis | < 1 second |

---

## SLI / SLO / SLA Definitions

### Matching Service
| SLI | SLO | SLA |
|-----|-----|-----|
| Match latency P99 < 3s | 99% of requests | 99.5% monthly |
| Match success rate | > 90% | > 85% monthly |

### Location Service
| SLI | SLO | SLA |
|-----|-----|-----|
| Location update ingestion P99 < 500ms | 99.9% of events | 99.5% monthly |
| Location query freshness < 30s | 99% of queries | 99% monthly |

### Trip Service
| SLI | SLO | SLA |
|-----|-----|-----|
| Trip state transition API P99 < 500ms | 99.5% of requests | 99% monthly |
| Trip completion rate | > 97% | > 95% monthly |

### Payment Service
| SLI | SLO | SLA |
|-----|-----|-----|
| Payment processing success | > 99.5% | > 99% monthly |
| Refund processing time | < 5 minutes | 95% of refunds |

---

## Prometheus Metrics — Key Metric Definitions

### Matching Service
```
# Match request rate
ride_matching_requests_total{city, status}

# Match latency histogram
ride_matching_latency_seconds{city, quantile}

# Driver candidates found per request
ride_matching_candidates_found{city}

# Active searches (in-flight matching)
ride_matching_active_searches{city}
```

### Location Service
```
# Location update ingestion rate
driver_location_updates_total{city, driver_tier}

# Redis GEO write latency
driver_location_redis_write_latency_seconds{city}

# Stale driver count (no update > 30s)
driver_location_stale_count{city}

# Active drivers per city
driver_active_count{city, status}
```

### Trip Service
```
# Trip state machine transitions
trip_state_transitions_total{from_state, to_state, city}

# Trip duration histogram
trip_duration_seconds{city, trip_type}

# Cancellation rate
trip_cancellations_total{cancelled_by, reason, city}
```

### Payment Service
```
# Payment attempt rate
payment_attempts_total{method, status}

# Payment processing latency
payment_processing_latency_seconds{gateway}

# Refund rate
payment_refunds_total{reason}
```

---

## Logging Strategy

### Structured Logging Format (JSON)
Every service emits JSON logs with mandatory fields:

```
{
  "timestamp": "ISO-8601",
  "level": "INFO|WARN|ERROR",
  "service": "matching-service",
  "traceId": "abc123",
  "spanId": "def456",
  "tripId": "trip_789",
  "driverId": "drv_123",
  "riderId": "usr_456",
  "city": "bangalore",
  "message": "Driver matched to rider",
  "matchLatencyMs": 1234,
  "candidatesEvaluated": 15
}
```

### Log Levels Policy
| Level | When to Use |
|-------|-------------|
| ERROR | Unhandled exception, payment failure, state machine illegal transition |
| WARN | Retry attempt, slow query > 1s, stale driver detected |
| INFO | Trip state changes, match success, payment processed |
| DEBUG | Individual candidate evaluation scores (disabled in production by default) |

### PII Handling in Logs
- Rider and driver names: **never log**.
- Phone numbers: **never log**.
- GPS coordinates: log at city-level granularity only in standard logs. Exact coords in audit logs (access-controlled).
- Payment amounts: log order of magnitude only in standard logs.

### Log Retention
| Log Type | Retention | Storage |
|----------|-----------|---------|
| Application logs | 30 days | Elasticsearch |
| Audit logs (payments, location) | 7 years | S3 Glacier |
| Security logs | 1 year | S3 Standard |

---

## Distributed Tracing

### Trace Coverage
Every inbound HTTP request and Kafka message generates a trace. Trace propagation via W3C TraceContext headers.

### Critical Trace Paths

**Full Trip Request Trace**:
```
API Gateway (entry)
  └── Trip Service (createTrip)
        ├── Quota/Auth check (sync)
        ├── Kafka publish: RideRequested (async — span ends here)
        └── Matching Service (triggered by Kafka)
              ├── Redis GEO query (getNearbyDrivers)
              ├── Score each candidate
              ├── Push notification to driver
              └── Kafka publish: DriverMatched
```

**Location Update Trace (high volume — sampled 0.1%)**:
```
Driver App → Location Service → Redis GEO write → Kafka publish
```

### Sampling Strategy
| Trace Type | Sampling Rate |
|------------|--------------|
| Ride request (full trip trace) | 100% |
| Location update | 0.1% (10M/min is too high to trace all) |
| Payment transaction | 100% |
| Error traces | 100% (force-sample on error) |
| Admin/internal operations | 10% |

---

## Dashboards

### Grafana Dashboard: City Operations (Primary On-Call View)

| Panel | Metric | Alert Threshold |
|-------|--------|-----------------|
| Ride requests/min | ride_matching_requests_total rate | — |
| Match rate % | matches / requests | < 85% → WARN, < 70% → CRITICAL |
| Match latency P99 | ride_matching_latency_seconds | > 3s → WARN, > 5s → CRITICAL |
| Active drivers map | driver_active_count by city | < 50 per city → WARN |
| Trip completion rate | trip_state_transitions COMPLETED / STARTED | < 95% → WARN |
| Payment failure rate | payment_attempts{status=failed} rate | > 2% → CRITICAL |

### Grafana Dashboard: Location Service Health

| Panel | Metric |
|-------|--------|
| Location updates/sec | driver_location_updates_total rate |
| Redis write latency P99 | driver_location_redis_write_latency_seconds |
| Stale driver count | driver_location_stale_count |
| Redis memory usage | redis_memory_used_bytes |
| Kafka consumer lag | kafka_consumer_group_lag (location topic) |

### Grafana Dashboard: Driver App Health

| Panel | Metric |
|-------|--------|
| WebSocket connections | active_websocket_connections |
| GPS update frequency distribution | histogram |
| Driver app crash rate | crash_events_total |
| Push notification delivery rate | push_delivery_success_rate |

---

## Alerting Rules

### Critical Alerts (PagerDuty — immediate wake-up)
| Alert | Condition | Response |
|-------|-----------|----------|
| Match rate collapse | match_rate < 60% for 2 min | Check Location Service, Matching Service pods, Redis |
| Payment gateway down | payment_success_rate < 90% for 1 min | Check gateway status, activate backup gateway |
| Location service lag | location_update_lag P99 > 5s for 2 min | Check Redis throughput, Kafka consumer lag |
| Trip service error surge | 5xx rate > 5% for 1 min | Check Trip Service pods, DB connections |
| Database connection exhaustion | pg_pool_connections_waiting > 50 | Scale connection pool, check slow queries |

### Warning Alerts (Slack — business hours response)
| Alert | Condition |
|-------|-----------|
| Match latency degrading | P99 > 2s (approaching 3s SLO) |
| Surge pricing not activating | High demand but surge_multiplier = 1.0 |
| Driver acceptance rate dropping | < 80% for 10 minutes |
| Kafka consumer lag growing | lag > 10,000 messages on trip-events |

---

## Incident Runbooks (Summary)

### Runbook: Match Rate Below 70%
1. Check: are Location Service pods running? (`kubectl get pods -n location`)
2. Check: Redis GEO query latency (`redis-cli SLOWLOG GET`)
3. Check: are drivers online? (Dashboard: active drivers per city)
4. Check: Matching Service Kafka consumer lag
5. If Location Service down: restart pods, verify Redis connectivity
6. Escalate to L2 (city ops) if driver supply is genuinely low

### Runbook: Payment Gateway Timeout
1. Check: gateway status page
2. Check: circuit breaker state (open/closed in dashboard)
3. If gateway down: activate backup payment processor (Stripe → Razorpay or vice versa)
4. Re-queue pending payments from DLQ after gateway recovery
5. Notify rider/driver of delay via push notification

---

## Interview-Level Discussion Points

- **Why sample location updates at 0.1%?** — 10M updates/minute = 166K/second. At 100% trace sampling, Jaeger would receive 166K spans/second just from location — impossible to store or query. 0.1% = 166 traces/second, still enough to debug latency issues.
- **How do you detect a stale driver (GPS not updating)?** — Redis key has TTL of 30s. On each location update, key is refreshed. Prometheus metric `driver_location_stale_count` queries how many drivers haven't updated in 30s (key expired).
- **How do you correlate a rider's complaint ("my driver was driving in the wrong direction") with traces?** — Trip ID is logged in every service. Grafana → Explore → filter by tripId → see all logs across services. Link to Jaeger trace from tripId.
- **What's your error budget?** — Match latency SLO 99% < 3s over 30 days. Error budget = 1% × 30 days × 24h × 60min = 432 minutes of "bad" minutes allowed per month. Track error budget burn rate to know if changes are risky.
- **How do you ensure PII doesn't leak into traces?** — OpenTelemetry span attribute allowlist. Custom SpanProcessor that scrubs attributes matching PII patterns (phone, email regex) before exporting. Audit quarterly.
