# 12 — Observability: Banking Core System

---

## Objective

Define observability strategy for a banking system where operational blindness leads to regulatory violations, financial loss, and customer harm — requiring financial-grade monitoring beyond standard web application observability.

---

## Banking Observability Priorities

Standard software: detect performance issues.
Banking software: detect financial anomalies, compliance violations, fraud patterns.

Priority order:
1. Financial correctness monitoring (ledger integrity, double-entry invariant)
2. Regulatory compliance monitoring (CTR filing timeliness, AML response SLA)
3. Transaction health (success rate, latency, error rates)
4. Infrastructure health (DB, Kafka, Redis)
5. Business metrics (volume, growth, product performance)

---

## Financial Integrity Monitoring

### Double-Entry Invariant Check

Most critical monitoring in banking:

```sql
-- Run every 1 minute
SELECT 
  currency,
  SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END) AS imbalance
FROM ledger_entries
WHERE created_at > NOW() - INTERVAL '1 minute'
GROUP BY currency
HAVING ABS(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END)) > 0;
```

If any imbalance detected: P1 immediate. Stop all transactions. Investigate.

### Account Balance Integrity Check

```sql
-- Run hourly (against read replica)
SELECT account_id, stored_balance, computed_balance,
       ABS(stored_balance - computed_balance) AS drift
FROM (
  SELECT a.id AS account_id,
         a.balance AS stored_balance,
         COALESCE(SUM(CASE WHEN l.entry_type = 'CREDIT' THEN l.amount 
                           ELSE -l.amount END), 0) AS computed_balance
  FROM accounts a
  LEFT JOIN ledger_entries l ON l.account_id = a.id
  GROUP BY a.id, a.balance
) reconciliation
WHERE ABS(stored_balance - computed_balance) > 0;
```

Any drift > 0: P1 alert. Balance does not match ledger history.

---

## Regulatory Compliance Monitoring

### CTR Filing Timeliness

Cash Transaction Reports for > ₹10L must be filed within 7 days:

```
Metric: ctr_filing_delay_days
  = (ctr_filed_at - transaction_completed_at) IN DAYS

Alert: If any CTR has delay_days > 6 (1 day before deadline)
Severity: P1 (regulatory violation imminent)
```

### AML Screening SLA

```
Metric: aml_screening_latency
  = (screening_completed_at - transaction_completed_at)

SLO: 95% of transactions screened within 1 hour
SLO: 99% of transactions > ₹10L screened within 10 minutes

Alert: If 1-hour SLO breached for > 1% of transactions
```

### Maker-Checker SLA

```
Metric: maker_checker_pending_duration
  = NOW() - submission_time FOR pending requests

Alert: If any critical maker-checker request pending > 8 hours during business hours
Alert: If approval rate < 80% (many rejections = process or data quality issue)
```

---

## Transaction Health Metrics

### Tier 1 Metrics (P1 Alert on Breach)

| Metric | Alert Threshold | Severity |
|---|---|---|
| Transaction success rate | < 99% for 5 minutes | P1 |
| Double debit detected | Any occurrence | P1 |
| Ledger imbalance | Any occurrence | P1 |
| Balance drift > 0 | Any occurrence | P1 |
| NEFT failure rate | > 1% | P1 |
| AML consumer lag | > 5,000 messages | P1 |

### Tier 2 Metrics (P2 Alert)

| Metric | Alert Threshold |
|---|---|
| Transaction p99 latency | > 3s |
| Maker-checker queue depth | > 500 pending |
| EOD batch duration | > 3 hours (early warning at 7h window) |
| Redis memory utilization | > 80% |
| DB connection pool | > 80% utilized |
| RTGS rejection rate | > 0.5% |

---

## Distributed Tracing

### Critical Trace: Fund Transfer

```
POST /transfers (customer request, authenticated)
  └── TransferController
       ├── AuthService (session validation: 2ms)
       ├── AccountService (balance check + lock: 5ms)
       ├── IdempotencyCheck (Redis: 1ms)
       ├── MakerCheckerCheck (is this above threshold? 2ms)
       ├── LedgerService (debit entry: 8ms)
       ├── LedgerService (credit entry: 8ms)
       ├── OutboxWrite (event: 3ms)
       ├── NEFTSubmission (NPCI API: 50-2000ms — variable)
       └── Response: 200 OK

Total: 80-2100ms (NEFT API dominates)
```

Trace shows: where is the latency. NEFT API slow? Our DB slow? Identify bottleneck.

### Trace Sampling

| Condition | Sampling Rate |
|---|---|
| Failed transactions | 100% |
| Transactions > ₹1L | 100% |
| RTGS transactions | 100% |
| Maker-checker workflow | 100% |
| International transfers | 100% |
| Successful retail transfers < ₹10K | 5% |

---

## Logging

### Structured Banking Log Format

```json
{
  "timestamp": "2026-01-15T10:23:45.123Z",
  "level": "INFO",
  "service": "transfer-service",
  "traceId": "trace-abc123",
  "transactionId": "txn-xyz789",
  "accountId": "ACC-123",
  "event": "FUND_TRANSFER_COMPLETED",
  "fromAccount": "ACC-123",
  "toAccount": "ACC-456",
  "amount": 5000000,
  "currency": "INR",
  "channel": "NET_BANKING",
  "makerStaffId": null,
  "checkerStaffId": null,
  "referenceNumber": "NEFT-REF-789"
}
```

### PII and Financial Data in Logs

**Never log**:
- Full account numbers (mask to last 4: `ACC-****1234`)
- IFSC code (log first 4 only: `HDFC****`)
- Customer PAN/Aadhaar
- Beneficiary mobile number (mask: `+91-****-1234`)
- Staff credentials

**Always log** (with masking):
- Transaction amounts (log full amount — required for audit; mask in non-audit logs)
- Account IDs (bank's internal IDs — safe to log)
- Transaction reference numbers
- Staff IDs (internal employee IDs — safe to log)

---

## Audit Log (Regulatory)

Banking audit logs: strictest requirements.

### Every Action Audited

```
Customer actions:
  - Login/logout (with IP, device, timestamp)
  - Balance inquiry (for high-value accounts > ₹10L)
  - Transfer initiation, modification, cancellation
  - Beneficiary add/delete
  - Profile change (address, nominee, contact)

Staff actions:
  - ALL actions (no exceptions for banking staff)
  - Login/logout with MFA type used
  - Account access (which account, why, for how long)
  - Transaction approval/rejection (with reason)
  - Configuration change (before/after values)
  - Report generation

System events:
  - EOD batch start/completion
  - AML flag creation/resolution
  - System error affecting transactions
  - Key rotation events
```

### Audit Log Chain

```
audit_log_entry {
  id: UUID,
  previousEntryId: UUID,          // Link to prior entry
  previousEntryHash: SHA256,      // Hash of previous entry
  currentHash: SHA256,            // Hash of this entry (excludes this field)
  timestamp: TIMESTAMPTZ,
  actorId: TEXT,
  actorType: CUSTOMER | STAFF | SYSTEM,
  action: TEXT,
  resourceType: ACCOUNT | TRANSACTION | CONFIG,
  resourceId: TEXT,
  details: JSONB,
  ipAddress: INET,
  deviceId: TEXT
}
```

Tamper detection: any modification to audit_log_entry → subsequent hash chain breaks → detectable.

Storage: Postgres (recent, queryable) + S3 with Object Lock (archive, WORM, 10 years).

---

## SLI / SLO / SLA

### SLIs (what we measure)

- Transaction success rate (% of initiated transactions that complete)
- Transaction latency (p99 end-to-end)
- AML screening completeness (% of transactions screened within SLA)
- Audit log write success rate (% of actions that have audit record)
- CTR filing timeliness (% filed within 7 days)

### SLOs (internal targets)

| SLI | Target |
|---|---|
| Transaction success rate | 99.9% |
| Transaction latency p99 | < 3s |
| AML screening within 1h | 99.5% |
| Audit log completeness | 100% (no exceptions) |
| CTR filing within 7 days | 100% (regulatory mandate) |
| System availability | 99.99% (< 52 min/year) |

### SLA (RBI / customer-facing)

- Availability: 24x7 (RBI mandate for internet banking)
- NEFT settlement: within 2 hours of submission batch
- RTGS settlement: real-time (T+30 minutes during RTGS hours)
- Refund: within 5 business days (RBI mandate)

---

## Dashboards

### Operations Dashboard (24x7 NOC)

```
┌──────────────────────────────────────────────────────────┐
│  TPS        │  Success %  │  P99 Latency │  Pending MC  │
│   2,347     │   99.94%    │    1.8s      │    234       │
├─────────────┼─────────────┼──────────────┼──────────────┤
│  AML Lag    │  NEFT Queue │  Ledger OK   │  DB Pool %   │
│   234 msg   │   1,234     │   ✓ Balanced │    67%       │
├─────────────┴─────────────┴──────────────┴──────────────┤
│  [Transaction Rate]  [Error Rate]  [Latency P99]        │
│  [AML Consumer Lag]  [NEFT Backlog]  [Maker-Checker]   │
└──────────────────────────────────────────────────────────┘
```

### Regulatory Compliance Dashboard (Compliance Team)

- CTR filings: today's count, status (filed/pending)
- STR filings: this month, this quarter
- AML screening SLA: % within 1h, % within 10min (> ₹10L)
- Maker-checker: avg approval time, rejection rate
- KYC completion rate: accounts awaiting KYC

### EOD Batch Dashboard (Operations)

- Batch start time, current step, progress %
- Accounts processed: X of Y
- Interest calculated: ₹X total
- EMIs processed: N accounts
- Estimated completion time
- Alert: < 2 hours remaining if not complete

---

## Alerting

| Severity | Response | Examples |
|---|---|---|
| P1 | Phone call, < 5 min, NOC + compliance | Ledger imbalance, double debit, CTR breach |
| P2 | PagerDuty Slack + phone, < 30 min | AML lag, NEFT failure spike, batch overrun |
| P3 | Slack alert, < 4 hours | Maker-checker queue deep, performance regression |
| P4 | Jira ticket | Minor metric drift, non-critical warnings |

**Banking P1**: Always involves compliance officer notification, not just engineering.

---

## Interview Discussion Points

- **"What's your most important monitoring metric?"** → Ledger double-entry invariant: SUM(debits) = SUM(credits). Any deviation = money created or destroyed → immediate P1
- **"How do you detect a bug that causes wrong balances?"** → Hourly reconciliation: stored_balance vs SUM(ledger_entries). Any drift = alert. Same check run daily for all accounts.
- **"How do you ensure CTR is filed on time?"** → Alert when CTR delay_days > 6; compliance dashboard shows all pending CTRs with days remaining; AML consumer SLA monitoring ensures transactions screened timely
- **"How do you prove your audit log is tamper-proof?"** → Hash chain: each entry hashes previous entry. Weekly verification job detects any chain break. S3 Object Lock prevents deletion. Immutable by design.
- **"What metrics do you check first when an incident is reported?"** → Financial integrity first: ledger balanced? Double debits detected? Then transaction success rate + error breakdown. Then infrastructure.
