# 12 — Observability: KYC / Identity Verification Pipeline

---

## Objective

Define logging, tracing, metrics, and alerting for the KYC pipeline. Observability in a KYC system serves two masters: engineering (debugging pipeline failures) and compliance (audit trail for regulators).

---

## Structured Logging

### Log Format (JSON)

```json
{
  "timestamp": "2024-01-15T10:30:15.123Z",
  "level": "INFO",
  "service": "kyc-service",
  "trace_id": "4bf92f3577b34da6",
  "request_id": "req_uuid",
  "application_id": "kyc_app_uuid",
  "user_id": "usr_abc123",
  "event": "STATE_TRANSITION",
  "from_status": "DOCUMENT_VERIFIED",
  "to_status": "LIVENESS_PENDING",
  "triggered_by": "kyc-pipeline",
  "step_type": "LIVENESS",
  "vendor": "ONFIDO",
  "duration_ms": 2150
}
```

**PII rule:** NO names, DOB, document numbers, or addresses in logs. `user_id` and `application_id` are allowed (non-PII identifiers). If PII context is needed for debugging: look up via `application_id` in the secure PII store (requires authorized access).

---

## Metrics (Prometheus + Micrometer)

### Business Metrics

```
# Application lifecycle
kyc_applications_submitted_total{kyc_tier} counter
kyc_applications_approved_total counter
kyc_applications_rejected_total{rejection_reason} counter
kyc_applications_manual_review_total{routing_reason} counter
kyc_application_duration_seconds{outcome} histogram  # E2E time from submission to decision

# Step performance
kyc_step_duration_seconds{step_type, vendor, result} histogram
kyc_step_retries_total{step_type, vendor} counter
kyc_step_failures_total{step_type, vendor, failure_reason} counter

# Manual review
kyc_review_queue_depth{priority} gauge
kyc_review_age_hours{priority} gauge  # Age of oldest unassigned case
kyc_review_decision_total{decision} counter

# Vendor health
kyc_vendor_circuit_state{vendor} gauge  # 0=closed, 1=half-open, 2=open
kyc_vendor_api_duration_seconds{vendor, step_type} histogram
kyc_vendor_api_errors_total{vendor, error_type} counter
kyc_vendor_cost_total{vendor, step_type} counter  # If vendor reports per-call cost

# Watchlist
kyc_watchlist_hits_total{list_name, match_type} counter
kyc_watchlist_false_positive_rate gauge  # Updated weekly by compliance team

# PII
kyc_pii_purge_total counter
kyc_pii_purge_errors_total counter
kyc_applications_pii_purge_pending gauge
```

### Infrastructure Metrics

```
kyc_db_connections_active gauge
kyc_kafka_consumer_lag{consumer_group, topic} gauge
kyc_redis_hit_rate{cache_type} gauge
kyc_kms_latency_seconds histogram
kyc_s3_fetch_duration_seconds histogram
```

---

## Dashboards (Grafana)

### Dashboard 1: KYC Pipeline Health

| Panel | Metric | Alert Threshold |
|---|---|---|
| Application throughput | `rate(kyc_applications_submitted_total[1h])` | < 10% of expected (campaign: alert if > 5x) |
| Approval rate | `approved / (approved + rejected)` | < 80% sustained (indicates vendor issue) |
| Manual review rate | `manual_review / submitted` | > 20% (vendor quality issue or threshold too low) |
| E2E automated time P95 | `kyc_application_duration_seconds{outcome=APPROVED}` | > 10 minutes |
| Step failure rate per vendor | Vendor error counters | > 5% |

### Dashboard 2: Vendor Performance

| Panel | Metric | Alert |
|---|---|---|
| OCR vendor latency P99 | Per vendor | > 10 seconds |
| Liveness vendor latency P99 | Per vendor | > 15 seconds |
| Circuit breaker state | `kyc_vendor_circuit_state` | Any vendor OPEN |
| Watchlist hit rate | `kyc_watchlist_hits_total / submitted` | > 5% (investigation) |
| Vendor error rate | Per vendor, per error type | > 2% |

### Dashboard 3: Compliance Operations

| Panel | Metric | Alert |
|---|---|---|
| Review queue depth | Per priority | HIGH > 20, MEDIUM > 100 |
| Oldest unreviewed case | `kyc_review_age_hours` | > 12 hours for HIGH, > 48 hours for MEDIUM |
| PII purge backlog | `kyc_applications_pii_purge_pending` | > 100 |
| Watchlist false positive rate | Gauge (manually updated) | > 15% |

---

## Distributed Tracing

OpenTelemetry traces the full path of a KYC application — from API submission to final outcome:

```
POST /kyc/applications (root span: 85ms)
├── IdempotencyCheck (5ms)
├── PiiEncryptionService.encrypt (45ms) ← KMS call
├── DB: INSERT kyc_application (20ms)
├── Kafka: produce kyc.application.submitted (10ms)
└── Response: 202 Accepted

[async] kyc-pipeline-orchestrator consumer (3ms)
└── Kafka: produce kyc.step.ocr.pending

[async] kyc-ocr-worker consumer (2150ms total)
├── S3: GetObject (120ms) ← document fetch
├── PiiDecryption (55ms) ← KMS call
├── VendorOCRCall: DigiLocker (1800ms) ← external vendor
├── DB: UPDATE verification_step (20ms)
├── DB: INSERT state_transition (15ms)
└── Kafka: produce kyc.step.completed (10ms)
```

**Trace propagation across Kafka:** The `trace_id` is propagated in Kafka message headers (`X-B3-TraceId`, `X-B3-SpanId`). The consumer creates a child span that shows the full timeline from submission to vendor response.

**Sampling:** 1% sampling for healthy flows; 100% sampling for failed flows and manual review cases.

---

## SLI / SLO Definitions

| SLI | SLO | Measurement |
|---|---|---|
| Application submission success rate | 99.9% | Rolling 30 days |
| Automated KYC completion time < 5 minutes | 95% of automated approvals | Rolling 7 days |
| Vendor OCR P99 latency < 10s | Per vendor, rolling 24h | — |
| Manual review SLA (HIGH priority < 4h) | 95% of HIGH cases | Rolling 7 days |
| Watchlist screening false positive rate | < 10% | Weekly compliance review |
| PII purge on time | 100% within retention deadline | Monthly audit |

---

## Compliance Audit Reporting

In addition to engineering observability, the KYC service produces compliance reports:

### Weekly Report

- Total applications by tier (BASIC/STANDARD/FULL)
- Approval/rejection/manual review breakdown
- Watchlist hits by list name
- Average time to decision (automated vs manual)
- Vendor performance (OCR accuracy rate, liveness confidence distribution)

### Monthly Report

- Manual review decisions by officer (operator performance)
- False positive rate for watchlist screening
- PII purge completions
- SLA compliance (% reviewed within 24h)

These reports are exported from Grafana as PDF or generated by a Spring Batch job → S3 → email to compliance team.

---

## Alerting Strategy

### P0 — Immediate page

| Alert | Condition |
|---|---|
| All OCR vendors unreachable | All circuit breakers OPEN for > 5 minutes |
| KMS decryption failures | > 10 failures in 1 minute |
| Invalid state transition attempted | Any single occurrence |
| PII accessed without authorization | Auth failure on `kyc:review_pii` scope > 5 times/minute |

### P1 — 15-minute response

| Alert | Condition |
|---|---|
| Manual review HIGH queue > 50 | Compliance SLA at risk |
| Vendor error rate > 5% | Degraded verification quality |
| KYC submission failure rate > 2% | Onboarding impacted |
| Consumer group lag growing | `kyc-ocr-worker` lag > 100 |

### P2 — Slack notification

| Alert | Condition |
|---|---|
| Watchlist hit rate spike | > 5% in 1 hour (unusual) |
| PII purge job failure | Any purge batch error |
| Campaign day throughput spike | > 3x normal submission rate (pre-warm workers) |

---

## Interview Discussion Points

- **How do you trace a KYC application end-to-end across Kafka consumers?** The `trace_id` (OpenTelemetry W3C `traceparent`) is propagated in Kafka message headers. Each consumer creates a child span. Jaeger or AWS X-Ray shows the entire timeline: API submission → Kafka consumer → S3 fetch → vendor call → DB update → next Kafka event. One trace per application
- **How does a compliance officer audit a specific KYC decision?** Using `application_id`: query state_transitions table for the full history. Each transition shows who triggered it (SYSTEM, which pod, or operator_id), when, and why. Vendor results stored in verification_steps.result JSONB. Full audit trail without accessing PII (transition history contains no PII)
- **How do you prove to a regulator that every application was watchlist-screened?** Automated compliance check: `SELECT COUNT(*) FROM kyc_applications a WHERE a.status = 'APPROVED' AND NOT EXISTS (SELECT 1 FROM verification_steps v WHERE v.application_id = a.application_id AND v.step_type = 'WATCHLIST_SCREENING' AND v.status IN ('PASS', 'MANUAL'))` — this query should always return 0. Run as a daily scheduled check; report to compliance team. Zero result = 100% watchlist screening compliance
- **What KPI would you watch to detect vendor quality degradation?** OCR confidence score distribution: `histogram_quantile(0.05, kyc_step_confidence_score)` — the 5th percentile of confidence scores. If this drops from 0.90 to 0.70, the vendor is returning lower-quality extractions (possibly due to model update). Alert: 5th percentile confidence < 0.85 sustained for 30 minutes
