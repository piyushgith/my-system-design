# 12 — Observability: Loan Origination & Servicing System

## Objective

Define logging, metrics, tracing, and alerting for a lending platform. Unlike real-time systems, monitoring here focuses on workflow completion rates, financial reconciliation health, and SLA tracking across multi-hour processes.

---

## Key Observability Challenges for Loan Systems

1. **Long-lived workflows:** An application takes 24 hours to decide, a loan lives 24 months. Traces span sessions and days — traditional distributed tracing tools don't handle this.
2. **Financial reconciliation:** The system is only "correct" if money flows match ledger records — this requires domain-specific reconciliation metrics.
3. **Regulatory SLA tracking:** Bureau check must complete within X hours; maker-checker within 24 hours. These SLAs are business requirements, not system SLAs.

---

## Metrics (Prometheus + Grafana)

### Origination Funnel Metrics

| Metric | Type | Labels | Alert |
|--------|------|--------|-------|
| `loan_applications_submitted_total` | Counter | productType | N/A |
| `loan_applications_by_status` | Gauge | status | N/A (informational) |
| `underwriting_duration_seconds` | Histogram | decision_channel (auto/maker_checker) | p95 > 1800s (30 min) |
| `bureau_pull_duration_seconds` | Histogram | bureau (CIBIL/Experian) | p95 > 10s |
| `bureau_pull_failures_total` | Counter | bureau, error_type | > 10/min |
| `maker_checker_tasks_overdue` | Gauge | teamId | > 0 |
| `conversion_rate_application_to_offer` | Gauge | productType | < 30% over 1h window |
| `conversion_rate_offer_to_acceptance` | Gauge | productType | N/A |

### Disbursement Metrics

| Metric | Type | Labels | Alert |
|--------|------|--------|-------|
| `disbursements_initiated_total` | Counter | N/A | N/A |
| `disbursements_completed_total` | Counter | N/A | N/A |
| `disbursements_failed_total` | Counter | failure_reason | > 0 |
| `disbursement_saga_duration_seconds` | Histogram | N/A | p95 > 7200s (2 hours) |
| `disbursement_sagas_stuck` | Gauge | status | > 0 (any stuck saga = P1) |

### Servicing Metrics

| Metric | Type | Labels | Alert |
|--------|------|--------|-------|
| `emi_collection_success_rate` | Gauge | N/A | < 90% on EMI day |
| `emi_bounce_rate` | Gauge | bounce_reason | > 10% |
| `nach_batch_submission_duration_seconds` | Histogram | N/A | > 1800s (30 min) |
| `overdue_loans_by_dpd_bucket` | Gauge | dpd_bucket (1-30, 31-60, 61-89, 90+) | N/A |
| `npa_rate` | Gauge | productType | > 5% |
| `collections_case_open_count` | Gauge | teamId | N/A |

### Financial Reconciliation Metrics

| Metric | Type | Labels | Alert |
|--------|------|--------|-------|
| `reconciliation_discrepancies_total` | Counter | reconciliation_type | > 0 |
| `ledger_balance_mismatch` | Gauge | N/A | > 0 |
| `portfolio_outstanding_balance` | Gauge | productType | N/A (informational) |

---

## SLI / SLO / SLA

| SLI | SLO | SLA (external) |
|-----|-----|----------------|
| Underwriting auto-decision time | 95% within 5 minutes | N/A (internal) |
| Maker-checker decision time | 95% within 24 hours | Communicated to borrower |
| Disbursement time post-acceptance | 95% within 2 hours | Promised to borrower |
| EMI collection attempt on due date | 99% attempted on due date | Regulatory requirement |
| Loan status API availability | 99.9% | Partner SLA |
| Application submission API availability | 99.9% | Marketing SLA |
| Reconciliation accuracy | 100% | Regulatory requirement |

**Error budget for application API (99.9%):** 43.8 minutes downtime/month. This is generous for a non-real-time system — disruption during campaigns would have business impact, but it's not instant revenue loss like trading.

---

## Dashboards (Grafana)

### Executive Dashboard

- Daily application volume vs target
- Approval rate (funnel: submitted → approved → disbursed)
- Portfolio outstanding amount
- NPA rate trend
- EMI collection success rate (on 1st of month: live dashboard)

### Operations Dashboard

- Maker-checker queue depth per team
- Tasks approaching SLA deadline (< 2 hours remaining)
- Disbursement sagas in PENDING (> 30 minutes since bank transfer)
- DLQ message counts (all topics)

### EMI Collection Dashboard (Critical on 1st of Month)

- NACH batch submission progress (% submitted)
- Bank result file ingestion progress (T+1)
- Collection success/bounce/pending counts
- Bounce reason breakdown
- Retry queue depth

### Financial Health Dashboard

- Reconciliation status (last reconciliation run: success/failure)
- Ledger balance trend
- Write-offs this month
- NPA bucket distribution

---

## Logging Strategy

### Structured JSON Logging (Spring Boot + Logstash encoder)

Every log line includes:
```json
{
  "timestamp": "2024-01-15T10:05:00.123Z",
  "level": "INFO",
  "service": "loan-application-service",
  "applicationId": "uuid",
  "borrowerId": "uuid",
  "action": "APPLICATION_SUBMITTED",
  "correlationId": "trace-abc123",
  "actor": "BORROWER",
  "duration_ms": 45
}
```

### Correlation ID Strategy

Correlation ID generated at API Gateway for every request. Propagated via:
- HTTP header: `X-Correlation-Id`
- Kafka message headers: `correlation-id`
- MDC (Mapped Diagnostic Context): automatically included in all log lines

Multi-session correlation: for a 24-hour underwriting workflow, the `applicationId` serves as the cross-session correlation key (not trace ID, which doesn't survive across sessions).

### PII in Logs

**Never log:** PAN number, Aadhaar, bank account number, income amount, bureau score.

Log instead:
- `applicantId` (opaque UUID)
- `applicationId` (opaque UUID)
- Status transitions, not underlying data

Logstash filtering rules applied before ELK ingestion:
- Pattern matching on PAN format: `[A-Z]{5}[0-9]{4}[A-Z]` → replaced with `[PAN-REDACTED]`
- Pattern matching on mobile: `[0-9]{10}` near loan context → `[MOBILE-REDACTED]`

---

## Distributed Tracing (OpenTelemetry + Jaeger)

### Trace Scope

Standard distributed traces work well within a single user session (< 1 hour). For loan lifecycle (days):

- **Short-lived traces:** single API request (application submission, status query)
- **Workflow tracking:** `applicationId` or `loanAccountId` as search key in Jaeger — find all spans for a loan's lifecycle
- **Saga tracking:** `sagaId` as trace baggage — all spans in disbursement saga linked

### Sampling

| Scenario | Sample Rate |
|----------|------------|
| Normal API requests | 5% |
| Application submission | 100% |
| Disbursement saga | 100% |
| EMI recording | 1% (high volume) |
| Errors | 100% |
| Admin operations | 100% |

High sampling on financial operations (disbursement, applications) — these are low-volume but high-value. Never compromise on tracing these flows.

---

## Alerting

### P0 — Immediate (PagerDuty, Wake-Up)

| Alert | Condition |
|-------|-----------|
| Disbursement saga stuck | `disbursement_sagas_stuck > 0` for > 30 minutes |
| Reconciliation failure | Any reconciliation run returns discrepancy > ₹0 |
| NACH batch not submitted | 8 AM on due date, batch not submitted |
| PostgreSQL primary down | Health check fails |
| DLQ: disbursement topic | Any message in `disbursement-saga-events-dlq` |

### P1 — Urgent (PagerDuty, Business Hours)

| Alert | Condition |
|-------|-----------|
| EMI collection success rate < 90% | On EMI processing day |
| Maker-checker SLA breach | Any task overdue |
| Bureau API failure rate > 50% | Over 10-minute window |
| Application approval rate drops 50% vs previous hour | Possible system issue |

### P2 — Non-Urgent (Slack)

| Alert | Condition |
|-------|-----------|
| DLQ: any other topic | Message count > 0 |
| Consumer lag > threshold | Non-financial consumers |
| Read replica lag > 30 seconds | Borrower portal may show stale data |
| Redis cache hit rate < 50% | Cache warming issue |

---

## Loan Lifecycle Tracing (Beyond Distributed Traces)

For auditing and debugging, the `audit_log` table IS the loan's observability record:

```sql
-- "Show me everything that happened to application ABC"
SELECT action, actor_id, actor_type, old_status, new_status, 
       change_payload, occurred_at
FROM audit_log
WHERE entity_type = 'LOAN_APPLICATION' AND entity_id = 'abc...'
ORDER BY occurred_at;
```

This single query gives the complete timeline of every state change, who triggered it, and when. More useful than distributed tracing for multi-day workflows.

---

## Financial Reconciliation Monitoring

Nightly reconciliation (K8s CronJob, 2 AM):

1. **Loan balance check:** `sum(repayment_records.principal_paid)` per loan = `original_principal - outstanding_principal`
2. **Ledger check:** sum of ledger entries per loan account = outstanding principal
3. **EMI schedule check:** sum of `amortization_schedule.status = PAID` principal components matches total principal paid

Any discrepancy:
- Saved to `reconciliation_failures` table
- Prometheus gauge `reconciliation_discrepancies_total` incremented
- P0 PagerDuty alert if discrepancy > ₹0

Zero tolerance for financial discrepancy — this is not a "within epsilon" situation.
