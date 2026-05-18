# 12 — Observability: Payment Gateway / Wallet System

---

## Objective

Define observability strategy for a payment system where operational blind spots translate to financial loss, regulatory violations, and trust damage — requiring higher monitoring fidelity than typical web applications.

---

## Payment Observability is Mission-Critical

Standard observability: detect performance problems.
Payment observability: detect financial anomalies before they compound.

Key differences:
- Business metrics are financial metrics (not just "requests per second")
- Audit logging is a regulatory requirement, not optional
- Reconciliation is an observability tool (not just an accounting function)
- Every alert must have clear financial impact

---

## Metrics: Financial First

### Tier 1: Financial Health Metrics (P1 Alert on Breach)

| Metric | Alert Condition | Severity |
|---|---|---|
| Payment success rate | < 98% over 5min | P1 |
| Double charge count | > 0 in any window | P1 |
| Settlement mismatch | > 0 | P1 |
| Ledger balance drift | SUM(debits) ≠ SUM(credits) | P1 |
| Payment processing rate | < 50% of baseline (15min avg) | P1 |
| Fraud flagged rate | > 2% of transactions | P1 |

### Tier 2: Operational Health Metrics (P2 Alert)

| Metric | Alert Condition |
|---|---|
| Payment gateway latency p99 | > 5s |
| Fraud service latency p99 | > 200ms |
| DB connection pool utilization | > 80% |
| Kafka settlement consumer lag | > 500 messages |
| Webhook delivery failure rate | > 1% |
| Redis balance cache miss rate | > 20% |

### Tier 3: Business Metrics (Dashboard / Daily Review)

- Transaction volume by payment method (card, UPI, wallet, NEFT)
- Revenue processed (GMV) by hour, day, month
- Merchant count, active merchants
- Refund rate by merchant (high refund rate = fraud signal)
- Chargeback rate (must stay < 0.1% for payment network compliance)
- P99 payment latency by payment method

---

## The Double-Entry Invariant Monitor

Most critical financial observability check:

```sql
-- Run every 5 minutes
SELECT 
  currency,
  SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END) AS total_debits,
  SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END) AS total_credits,
  ABS(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END)) AS imbalance
FROM ledger_entries
WHERE created_at > NOW() - INTERVAL '5 minutes'
GROUP BY currency
HAVING ABS(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END)) > 0;
```

If any row returned: P1 alert. Money has appeared or disappeared in the ledger.

---

## Distributed Tracing

### Critical Trace: Payment Lifecycle

```
POST /payments (user request)
  └── PaymentController (Spring Boot)
       ├── IdempotencyCheck (Redis: 1ms)
       ├── FraudPreCheck (FraudService: 50-100ms)
       ├── WalletDebit (Redis DECRBY: 1ms)
       ├── PaymentGatewayCharge (Stripe API: 200-5000ms)
       ├── LedgerWrite (Postgres: 5-10ms)
       └── OutboxWrite (Postgres: 2ms)

→ Total: 300-5200ms (gateway latency dominates)
→ Trace shows: which step is slow, which step failed
```

For every failed payment: trace shows exact failure point. No guessing from logs.

### Trace Sampling Policy

| Transaction | Sampling Rate | Reason |
|---|---|---|
| Failed payments | 100% | Every failure needs investigation |
| Payments > ₹10,000 | 100% | High-value; full audit |
| Fraud-flagged | 100% | Security investigation |
| Latency > 2s | 100% | Performance investigation |
| Successful < ₹1,000 | 1% | High volume; statistical sampling OK |

---

## Logging

### What to Log (Payment-Specific)

**Payment initiation**:
```json
{
  "event": "PAYMENT_INITIATED",
  "paymentId": "pay_xyz",
  "userId": "usr_abc",
  "amount": 250000,
  "currency": "INR",
  "paymentMethod": "CARD",
  "merchantId": "mer_789",
  "idempotencyKey": "idem_key_hash",
  "ip": "203.x.x.x",
  "deviceId": "dev_fingerprint_hash"
}
```

**Payment result**:
```json
{
  "event": "PAYMENT_CAPTURED",
  "paymentId": "pay_xyz",
  "gatewayTransactionId": "ch_stripe_xyz",
  "capturedAt": "2026-01-15T10:23:45Z",
  "processingTimeMs": 1234
}
```

**Never log**:
- Card numbers (even partial beyond last4)
- CVV
- Bank account numbers (beyond last4)
- API secret keys
- Full JWT tokens

### Log Masking

Custom Logback filter:

```
Card number pattern: [0-9]{13,16} → ****
API key pattern: sk_live_[a-zA-Z0-9]+ → sk_live_****
Bank account: [0-9]{9,18} → ****
```

Automated PII scanning in log pipeline (AWS Macie or Elasticsearch ingest pipeline) — detect and alert if PII appears in logs unmasked.

---

## Audit Log (Regulatory)

Separate from operational logs. Immutable. Required by RBI/SEBI/PCI DSS.

Every financial event written to audit log:

| Event | Required Fields |
|---|---|
| Payment initiated | userId, paymentId, amount, currency, method, IP, timestamp |
| Payment captured | paymentId, gatewayRef, capturedAt, processingTimeMs |
| Payment failed | paymentId, failureCode, failureReason, gatewayError |
| Refund issued | paymentId, refundId, amount, reason, initiatedBy |
| Ledger entry | entryId, type, amount, currency, accountId, beforeBalance, afterBalance |
| Fraud flagged | paymentId, score, signals, action |
| Admin access | adminId, action, resourceId, IP, timestamp |

**Storage**: S3 with Object Lock (WORM — Write Once Read Many). Retention: 7 years minimum (RBI mandate).

**Tamper evidence**: audit event hash chain. Each event includes `previousEventHash`. Independent verification tool can detect any tampering.

---

## Alerting and Escalation

### Alert Routing

| Severity | Route | Response Time |
|---|---|---|
| P1 (financial anomaly) | PagerDuty → on-call engineer + finance lead phone | < 5 minutes |
| P1 (double charge detected) | PagerDuty → engineering + finance + legal phone | < 5 minutes |
| P2 (performance degradation) | PagerDuty → on-call Slack + phone | < 30 minutes |
| P3 (elevated fraud) | Slack #fraud-alerts + fraud team | < 2 hours |
| P4 (minor anomaly) | Slack #monitoring | Next business day |

### Alert Contents

Every alert includes:
- Metric name + current value + threshold
- Time of first detection
- Financial impact estimate (e.g., "₹X in payments affected")
- Runbook link
- Dashboard link
- Correlation ID if specific payment

---

## SLI / SLO

### Payment-Specific SLIs

| SLI | Measurement |
|---|---|
| Payment API availability | % of payment requests returning non-5xx |
| Payment success rate | % of initiated payments that are captured |
| Payment latency | p99 time from initiation to captured response |
| Settlement timeliness | % of settlements processed within T+1 |
| Refund processing time | % of refunds completed within 5 business days |

### SLOs

| SLI | Target | Error Budget |
|---|---|---|
| Payment API availability | 99.99% | 52 min/year |
| Payment success rate | 99.5% | 0.5% failure allowed |
| Payment p99 latency | < 3s | - |
| Settlement T+1 rate | 99.9% | - |

**Payment API availability at 99.99%**: this is non-negotiable. Downtime = revenue loss × merchant SLA breach × regulatory scrutiny.

---

## Dashboards

### Real-Time Operations Dashboard

```
┌────────────────────────────────────────────────────────────┐
│  Payments/min  │  Success Rate  │  P99 Latency  │  Fraud % │
│    4,523       │    99.7%       │    1.8s       │   0.12%  │
├────────────────┼────────────────┼───────────────┼──────────┤
│  Active Wallets│  Redis Balance │  DB Pool %    │  DLQ Size│
│    1.2M        │   Cache 94%    │    67%        │    0     │
├────────────────┴────────────────┴───────────────┴──────────┤
│  [Payment Rate Graph]  [Success Rate Graph]  [Latency P99] │
│  [Gateway Latency]     [Fraud Rate Graph]    [Kafka Lag]   │
└────────────────────────────────────────────────────────────┘
```

### Reconciliation Dashboard

Daily view:
- Settlement file received: Yes/No
- Matched transactions: N
- Unmatched in our DB: N (expected — T+1 lag)
- Unmatched in bank file: N (investigate if > 0)
- Amount mismatches: N (P1 if > 0)
- Last successful reconciliation timestamp

### Fraud Dashboard

Real-time:
- Flagged transactions/hour (by ML score bucket)
- Auto-declined transactions
- Manual review queue depth
- Top fraud patterns (geographic, merchant, payment method)

---

## Synthetic Monitoring

For payment systems: synthetic transactions run continuously:

```
Every 5 minutes:
  1. Create test wallet (with test funds)
  2. Initiate test payment (to test merchant)
  3. Verify payment captured within SLA
  4. Verify notification received
  5. Initiate refund
  6. Verify refund processed

→ If any step fails: P1 alert immediately
→ Proves end-to-end payment flow is healthy, not just individual services
```

Synthetic payments use dedicated test accounts; flagged in our DB; excluded from business metrics.

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| 100% trace sampling for failures | Complete audit for every issue | Storage cost (mitigate with Jaeger compression) |
| Separate audit log store | Regulatory compliance, tamper-proof | Additional infrastructure |
| Real-time double-entry check | Instant financial anomaly detection | DB query every 5 minutes |
| Synthetic monitoring | Proactive detection before users | Engineering maintenance |

---

## Interview Discussion Points

- **"How do you detect if a bug caused money to disappear?"** → Double-entry invariant monitor every 5 minutes; SUM(debits) ≠ SUM(credits) → immediate P1 alert
- **"How do you comply with RBI audit requirements?"** → Immutable S3 audit log with 7-year retention; WORM storage; hash chain tamper evidence
- **"What's your on-call response for a double charge?"** → P1 phone alert to engineering + finance + legal; auto-reverse second charge; user notification within 15 minutes
- **"How do you prove the system is working correctly without users reporting issues?"** → Synthetic monitoring: full payment flow tested every 5 minutes; alerts before users notice
- **"What metrics are more important than P99 latency?"** → Payment success rate, fraud rate, settlement timeliness, double-entry invariant. Latency matters but financial correctness metrics come first.
