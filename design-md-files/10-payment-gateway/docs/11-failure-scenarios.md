# 11 — Failure Scenarios: Payment Gateway / Wallet System

---

## Objective

Analyze failure modes specific to payment systems — where bugs cause financial loss, not just downtime — and define detection, recovery, and prevention strategies with zero tolerance for data loss.

---

## Payment Failures Are Different

In e-commerce, a failure means a user has a bad experience.
In payments, a failure means:
- Money disappears from user's account without completing transaction
- User is charged twice
- Merchant receives payment but order not fulfilled
- Regulatory violations with financial penalties

**This changes the failure handling philosophy**: prefer availability loss over data loss or inconsistency.

---

## Scenario 1: Double Charge (Highest Severity)

**Trigger**: Client retries payment POST after timeout. Server processed first request successfully but response was lost.

**Impact**: User charged twice. Financial loss + trust destruction.

**Prevention (primary)**:
1. Idempotency key required on all payment APIs
2. Key stored in Redis for 24h + Postgres (durable backup)
3. Duplicate request → return cached response; no re-processing

**Detection**:
- Alert: payment_initiated_total > payment_result_total × 1.01 (more initiations than results)
- Reconciliation: daily comparison of our records vs bank settlement file

**Recovery**:
- Identify duplicate: same idempotency key, two ledger entries
- Auto-reverse second charge (payment reversal API call to card network)
- Notify user + issue compensation

---

## Scenario 2: Money Debited, Credit Not Applied (Wallet Transfer)

**Trigger**: Redis successfully decrements sender balance. App crashes before incrementing receiver balance. Postgres write pending.

**Impact**: Money leaves sender's wallet; never arrives in receiver's wallet. Money "disappears."

**Prevention**:

```
Saga pattern for wallet transfer:
  Step 1: Debit sender (Redis DECRBY + outbox event)
  Step 2: Credit receiver (consumed from Kafka event)

If Step 2 fails:
  Compensation: Credit sender back (undo debit)
  Both sides see: transaction failed, balances restored
```

Outbox pattern ensures Step 1's event is published to Kafka even if app crashes.

**Detection**:
- Reconciliation: SUM(all credits) must equal SUM(all debits) for each currency
- Run every 5 minutes on live system; alert on any imbalance > ₹0
- Daily reconciliation against bank statement

**Recovery**:
- Identify: sender debited, receiver not credited, no compensation applied
- Manual investigation: is receiver credit in Kafka DLQ? Apply manually.
- Credit receiver; update ledger entries; notify both parties

---

## Scenario 3: Payment Gateway Timeout

**Trigger**: Stripe/Razorpay API times out. Unknown if payment was processed.

**Impact (without mitigation)**: Money debited locally but card not actually charged — OR — card charged but our system doesn't know.

**Mitigation**:
1. Async payment flow: debit wallet OR initiate charge; return "processing" to user
2. Poll payment gateway status API on timeout: `GET /payment/{externalId}/status`
3. Webhook-based confirmation: gateway sends webhook when payment settles
4. Timeout + no webhook after 30 minutes → query gateway status API → update

**State machine protection**:
```
INITIATED → PROCESSING → AUTHORIZED → CAPTURED → SETTLED
                       → FAILED
                       → CANCELLED (timeout + no confirmation)
```

States are one-directional. Cannot go from CAPTURED back to FAILED.

**Recovery**:
- INITIATED for > 30 minutes with no update: trigger status check job
- Status: succeeded → update to CAPTURED, credit merchant
- Status: failed → update to FAILED, refund debit if applicable
- Status: unknown → escalate to manual review

---

## Scenario 4: Fraud Detection Service Down

**Trigger**: Fraud service pod crash or latency spike.

**Impact choices**:
1. Block all payments until fraud service recovers → revenue impact
2. Allow all payments without fraud check → fraud loss

**Decision**: circuit breaker with calibrated fail-open policy:

| Transaction Risk | Fraud Service Down Action |
|---|---|
| < ₹1,000, established user | Auto-approve (low risk) |
| ₹1,000-₹10,000, established user | Apply rule-based checks only |
| > ₹10,000 any user | Require additional auth (OTP) |
| New user, any amount | Require additional auth (OTP) |
| > ₹1,00,000 | Block until fraud service recovers |

Circuit breaker opens after 5 consecutive fraud service failures.

**Recovery**: fraud service comes back → retroactively score payments that went through with bypass → flag/reverse if high-risk.

---

## Scenario 5: Postgres Primary Failure

**Trigger**: DB instance OOM, disk failure, network partition.

**Impact (payment-grade)**:
- All write operations fail: payments can't be recorded
- If replica promoted with lag: some committed transactions may not be in replica

**RDS Multi-AZ behavior**:
- Synchronous standby: zero committed transaction loss
- Automatic failover: < 60s (DNS update)

**Application behavior during failover**:
- Connection pool retries with exponential backoff
- Payments in-flight return 503 with `Retry-After: 30`
- Idempotency key ensures retry doesn't double-charge

**Data loss risk**:
- Multi-AZ synchronous replication: RPO = 0 (no committed transaction loss)
- Read replicas (async): possible loss on failover — never use for payment writes

**Recovery**:
- Automatic (RDS): replica promoted
- Application: reconnects via same endpoint after DNS propagates
- Payments that returned 503: client retries with same idempotency key → safe

---

## Scenario 6: Settlement File Mismatch

**Trigger**: EOD settlement file from bank shows different amounts than our internal records.

**Impact**: Financial loss (if ours is higher) or liability (if bank's is higher).

**This is expected** — settlement reconciliation is routine operations work.

**Process**:
1. Download settlement file from bank SFTP
2. Parse: transaction_id, amount, status
3. Compare against our payments table
4. Generate discrepancy report:
   - In our DB but not in bank file: pending settlement (normal, 1-2 days)
   - In bank file but not in our DB: mystery credit (investigate immediately)
   - Amount mismatch: most critical — possible truncation/rounding error

**Reconciliation service** runs at 3 AM daily:
- Matches on transaction reference number
- Generates report: matched, unmatched, amount-mismatch
- Alerts sent to finance team with Jira tickets for each discrepancy

---

## Scenario 7: Redis Down (Balance Cache Lost)

**Trigger**: Redis Cluster loses majority of nodes (network partition, multiple failures).

**Impact**:
- Balance reads fail (can't serve balance from cache)
- Wallet transfers fail (Redis atomic DECR unavailable)

**Fallback**:

Balance reads:
→ Fall back to Postgres: `SELECT balance FROM wallets WHERE id = ?`
→ Slower (5-10ms vs 0.5ms) but correct

Wallet DECR:
→ Fall back to Postgres `SELECT FOR UPDATE`:
```sql
BEGIN;
SELECT balance FROM wallets WHERE id = ? FOR UPDATE;
-- check sufficient funds
UPDATE wallets SET balance = balance - ? WHERE id = ?;
COMMIT;
```
→ Row-level lock; correct but serializes concurrent transfers to same wallet

**Risk**: Under Redis failure, all transfers to popular wallets serialize through Postgres. Latency spikes. If Redis is down for > 5 minutes: engineer escalation; Redis recovery is P1.

---

## Scenario 8: Kafka Consumer Lag on Critical Topics

**Trigger**: Settlement consumer falls behind; notification consumer backlogged.

**Impact**:
- Merchants receive payment confirmation hours late
- Settlement processing delayed → merchant cash flow impact

**Mitigation**:
- Alert: consumer lag > 1,000 messages on payments.payment.captured (settlement topic)
- Scale: add consumer instances (up to partition count: 16)
- Prioritize: settlement consumer gets dedicated pod pool; not shared with analytics

**Kafka lag is a regulatory issue for payment systems**: if settlement is delayed > 2 business days, RBI can issue compliance notice.

---

## Scenario 9: Refund Already Processed (Idempotency Failure)

**Trigger**: Support agent clicks "Process Refund" twice in 3 seconds.

**Impact**: User receives double refund. Merchant account incorrectly debited twice.

**Prevention**:
- Idempotency on refund API: `Idempotency-Key: refund-{originalPaymentId}-{timestamp}`
- UI: disable "Process Refund" button after first click (client-side)
- DB constraint: `UNIQUE(payment_id, refund_type)` where refund_type = 'FULL' allows only one full refund

---

## Cascading Failure Prevention

### Circuit Breaker Configuration

```
Payment gateway circuit breaker:
  CLOSED state: all requests pass through
  OPEN condition: 5 failures in 10s → OPEN
  OPEN state: reject all calls immediately, return fallback
  HALF_OPEN condition: after 30s, try 1 request
  CLOSE condition: 3 consecutive successes → CLOSED

Fraud service circuit breaker:
  Same pattern; fallback = risk-based manual rules
```

### Bulkhead Pattern

```
Payment processing thread pool: 50 threads (payment gateway calls)
Notification thread pool: 20 threads (email/SMS)
Analytics thread pool: 10 threads (low priority)
```

Notification slowdown cannot exhaust payment processing threads.

### Timeout Discipline

| External Call | Timeout | On Timeout |
|---|---|---|
| Payment gateway | 10s | Async retry; return "processing" to user |
| Fraud service | 100ms | Circuit open; apply fallback rules |
| Bank transfer API | 30s | Mark PENDING; poll status |
| Notification service | 2s | DLQ for retry; not blocking |

---

## Recovery Runbooks

| Failure | Detection | Recovery |
|---|---|---|
| Double charge | Reconciliation mismatch | Auto-reverse second charge; user notification |
| Missing credit | Balance reconciliation | Apply credit manually; ledger correction |
| Postgres down | DB connection failures | RDS Multi-AZ auto-failover |
| Redis down | Redis health check | Fall back to Postgres SERIALIZABLE for transfers |
| Settlement mismatch | Nightly reconciliation report | Finance team investigation + manual correction |
| Kafka lag | Consumer lag alert | Scale consumers; prioritize settlement |

---

## Interview Discussion Points

- **"What's worse: double charge or system downtime?"** → Double charge is worse for trust and legally riskier; downtime is recoverable
- **"How do you detect if money disappeared?"** → Double-entry invariant: SUM(debits) = SUM(credits) always; reconciliation job every 5 minutes
- **"What's your RTO/RPO for payment DB failure?"** → RPO = 0 (RDS Multi-AZ synchronous replication); RTO < 60s (failover)
- **"How do you handle the fraud service being down?"** → Circuit breaker + tiered fallback: low-value established user = allow; high-value = require OTP; very high-value = block
- **"How do you reconcile with the bank?"** → Nightly job: download settlement file, match by transaction reference, generate discrepancy report; finance reviews P1 cases next morning
