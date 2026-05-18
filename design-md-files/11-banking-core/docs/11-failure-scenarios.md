# 11 — Failure Scenarios: Banking Core System

---

## Objective

Analyze failure modes in a banking core system — where failures can cause regulatory violations, financial loss, and customer harm — and define detection, recovery, and prevention strategies with zero tolerance for data loss.

---

## Banking Failure Philosophy

Banking failure handling differs from typical web systems:

| Web System | Banking System |
|---|---|
| Fail fast, fail cheap | Fail safe, fail correct |
| Retry immediately | Retry idempotently with audit trail |
| Downtime = bad UX | Downtime = regulatory violation |
| Data loss = incident | Data loss = legal liability + RBI action |
| Rollback easy | Rollback requires compensating transactions |

---

## Scenario 1: Postgres Primary Failure During Transaction

**Trigger**: DB instance failure mid-transaction — after customer receives "transfer successful" but before commit.

**Impact**: Customer sees success, DB has no record. Money appears to disappear.

**Prevention**:
- RDS Multi-AZ: synchronous standby — committed transactions are safe
- Response only sent to customer after DB commit confirmed
- In-flight transactions at failure time: client receives 503 → retries with idempotency key

**What "committed" means**:
```
DB write committed → Response sent to client
If DB write fails → 5xx returned → Client never sees "success"
If network drops after commit → Client retries → Idempotency key prevents duplicate
```

**Recovery**:
- RDS Multi-AZ failover: automatic, < 60s
- Application reconnects via same endpoint (DNS update)
- Idempotent retry: safe because idempotency key present

**Data loss risk**:
- Multi-AZ synchronous replication: RPO = 0 (zero committed transaction loss)
- Read replicas (async): do NOT use for failover target; may have replication lag

---

## Scenario 2: Maker Approves But System Crashes Before Execution

**Trigger**: Checker approves maker-checker request. Event emitted to Kafka. App crashes before consuming and executing.

**Impact**: Request shows "APPROVED" but transfer never executed. Amounts not moved.

**Prevention**:
- State machine: SUBMITTED → APPROVED → EXECUTING → EXECUTED
- Outbox pattern: checker approval emits event atomically with DB state update
- Execution consumer: idempotent — checks if already EXECUTED before proceeding
- Timeout sweeper: APPROVED requests older than 30 minutes with status still APPROVED → alert

**Recovery**:
- Kafka consumer comes back online → processes APPROVED event → executes
- If Kafka event lost (shouldn't happen with replication factor 3): sweeper job detects stale APPROVED → re-emits event
- Manual execution path: admin portal can force-execute APPROVED requests with dual-approval

---

## Scenario 3: EOD Batch Overrun

**Trigger**: Interest calculation batch doesn't complete before branch opening (9 AM). Takes 6 hours instead of 4.

**Impact**:
- Branch tellers cannot see correct balances
- Customer passbook updates delayed
- Regulatory reporting delayed

**Detection**:
- Alert at 7 AM: "EOD batch not complete with 2 hours remaining"
- Escalation at 8 AM: "EOD batch not complete with 1 hour remaining" → P1

**Root causes and fixes**:
| Root Cause | Fix |
|---|---|
| Unpartitioned interest query on 50M accounts | Partition account processing by account range; parallelize |
| Single Postgres doing OLAP during OLTP | Dedicated analytics replica for batch; never run on primary |
| Lock contention | Chunk updates: 10,000 accounts per transaction; not one massive UPDATE |
| Unexpected data growth | Increase batch worker capacity; monitor batch duration trend weekly |

**Recovery**:
- Delay branch opening by 30 minutes (last resort; requires management approval)
- Run partial batch: complete priority accounts (salary accounts, large depositors)
- Remaining accounts batch completes during day (low-priority interest credit)

---

## Scenario 4: NEFT/RTGS Submission Fails

**Trigger**: RBI NPCI API is unavailable. NEFT transactions are stuck.

**Impact**: Customer submitted transfer, it's debited from their account, but not sent to beneficiary.

**State machine protection**:
```
NEFT transfer:
  State: INITIATED → SUBMITTED_TO_NPCI → ACKNOWLEDGED → SETTLED
                   → NPCI_UNAVAILABLE (fallback state)
                   → FAILED
                   → REVERSED (if failed, debit reversed)
```

**Mitigation**:
- NEFT queue: transactions collected, submitted in batches to NPCI (NEFT operates in hourly batches)
- On NPCI unavailability: transactions held in NEFT_QUEUED state
- Retry when NPCI recovers (NEFT batches run every hour)
- Customer communication: "Transfer queued; will be processed in next NEFT batch"

**If NPCI unavailable > 3 hours** (during business day):
- Alert compliance + operations
- Communication to customers: "NEFT services temporarily affected"
- RBI notification if > 4 hours (regulatory requirement)

**Recovery**:
- NPCI recovers → queue processor resumes → submit all queued NEFT transactions
- If transaction ultimately fails: automatic debit reversal + SMS to customer

---

## Scenario 5: AML System Down During High-Volume Day

**Trigger**: AML screening service crashes during peak transaction period.

**Impact**: Transactions completing without AML screening — regulatory violation risk.

**Decision: Fail-open vs Fail-closed**:

| Decision | Implication |
|---|---|
| Fail-open (allow transactions) | Revenue continues; AML screening delayed; regulatory risk if flagged transaction missed |
| Fail-closed (block transactions) | Zero revenue during AML downtime; customers cannot transact |

**Banking answer**: neither extreme. Tiered approach:

```
AML service down (circuit breaker open):
  
  Low-risk transactions (< ₹10,000, known beneficiary, KYC-complete customer):
    → Allow, log "AML_DEFERRED", queue for retroactive screening

  Medium-risk (₹10,000 - ₹10L, new beneficiary):
    → Allow with enhanced logging; retroactive screening within 30 minutes

  High-risk (> ₹10L, international, new customer):
    → Block until AML service recovers (< 30 minutes target)
    → Customer: "Large transfer temporarily unavailable; please try in 30 minutes"
```

**Recovery**:
- AML service recovers
- Process deferred queue: retroactive screening of all AML_DEFERRED transactions
- Flag any that would have been blocked; report to compliance officer
- File STR if required (even retroactively)

---

## Scenario 6: Double Debit on Fund Transfer

**Trigger**: Network timeout after DB commit. Client retries. Two debits recorded.

**Impact**: Customer debited twice. Financial loss.

**Prevention (same as payment gateway)**:
- Idempotency key required on all transfer APIs
- Server stores result for 24h
- Duplicate = return cached result

**Detection**:
- Real-time: duplicate transaction alert (same from+to+amount within 5 seconds)
- Reconciliation: daily account balance check (SUM(credits) - SUM(debits) = current balance)

**Recovery**:
- Auto-reversal: if detected within 1 hour, system auto-reverses second debit
- After 1 hour: manual review → customer credit + apology
- If beneficiary already spent money: inter-bank recovery process (complex, slow)

---

## Scenario 7: Unauthorized Access to Account

**Trigger**: Compromised credentials (phishing); attacker initiates transfer.

**Detection**:
- Anomaly detection: transfer to new beneficiary from unusual location/device
- Step-up auth triggered: OTP required for new beneficiary
- If OTP compromised: AML detects unusual pattern → flag

**Response timeline**:
- T+0: Suspicious transfer initiated
- T+1min: AML system flags based on velocity/pattern
- T+5min: Auto-block account (if high confidence fraud)
- T+10min: SMS to customer: "Transaction blocked; call us if not you"
- T+30min: Compliance officer reviews; confirms fraud
- T+2h: Transaction reversal initiated (if funds haven't left bank)

**If funds transferred out**:
- Internal recovery: if beneficiary account in same bank, freeze immediately
- External recovery: alert beneficiary bank via SWIFT/RBI channel
- Police complaint filed by bank
- Insurance: banks carry cyber fraud insurance

---

## Scenario 8: Kafka Consumer Lag on AML Topic

**Trigger**: AML consumer falls 10,000 messages behind on banking.transaction.completed.

**Impact**: Transactions completing without timely AML screening.

**Regulatory implication**: RBI requires CTR filing within 7 days. If consumer lag means CTR not filed in time: regulatory violation.

**Mitigation**:
- Alert: AML consumer lag > 1,000 messages
- Scale: add AML consumer instances (up to partition count: 8)
- Priority partition: large transactions on dedicated partition; small-value on others
- Consumer health check: circuit breaker if AML service itself is slow

**SLA**:
- < ₹10L transactions: AML screening within 1 hour acceptable
- > ₹10L transactions: AML screening within 5 minutes (CTR requirement)
- Priority routing: large transactions to dedicated Kafka partition with dedicated consumer

---

## Scenario 9: Core Banking System Integration Failure

**Trigger**: External Core Banking System (Temenos, Finacle) API unavailable.

**Impact**: Cannot create accounts, process loans, access central records.

**Context**: Many banks have a legacy CBS + modern microservices wrapper.

**Mitigation**:
- Local cache of recent CBS data (account status, balance) for read-only operations
- Degrade gracefully: read-only mode (balances readable, transfers blocked)
- Queue transfers: hold in local DB; sync to CBS when available
- Customer: "Account opening temporarily delayed; transfers limited to existing beneficiaries"

**Recovery**:
- CBS recovers → sync queue → process pending operations
- Conflict resolution: CBS is system of record; local changes reconciled against CBS

---

## Cascading Failure Prevention

### Circuit Breakers

```
NEFT gateway circuit breaker:
  5 failures in 60s → OPEN (queue mode)
  30s half-open check
  3 successes → CLOSED (normal mode)

AML service circuit breaker:
  3 failures in 30s → OPEN (tiered degrade mode)
  30s half-open
  2 successes → CLOSED

CBS integration circuit breaker:
  5 failures in 120s → OPEN (read-only mode)
```

### Bulkhead Pattern

```
NEFT processing thread pool: 20 threads
RTGS processing thread pool: 10 threads (high-value, separate)
AML screening thread pool: 30 threads
Batch processing thread pool: 10 threads (background, can be starved)
```

Batch processing starved before customer-facing operations. RTGS (high-value) never starved.

---

## Recovery Runbooks Summary

| Failure | Detection | Recovery Time | Action |
|---|---|---|---|
| Postgres primary failure | DB connection failures | < 60s | RDS Multi-AZ auto-failover |
| EOD batch overrun | Batch duration alert at 7 AM | 30-120 min | Prioritize + parallelize |
| NEFT API down | NEFT submission failure rate | Ongoing | Queue mode; retry when NPCI recovers |
| AML system down | Consumer lag alert | 15-30 min | Tiered fail-open; retroactive screening |
| Double debit | Reconciliation + duplicate alert | < 1h | Auto-reversal if detected early |
| Account fraud | Anomaly detection + AML | Minutes | Account block + reverse + notify |
| CBS unavailable | API timeout on CBS calls | 30-120 min | Read-only mode; queue operations |

---

## Interview Discussion Points

- **"What's your RTO/RPO for the banking core DB?"** → RPO = 0 (RDS Multi-AZ synchronous replication); RTO < 60s (automated failover); zero committed transaction loss
- **"What do you do if AML is down but customers want to transfer?"** → Tiered fail-open: low-value = allow with deferred screening; high-value = block. Retroactive screening when AML recovers. Document all deferrals for compliance officer review.
- **"How do you handle EOD batch overrun?"** → Alerting at 7 AM; parallelize by account range; dedicated replica for batch (no OLTP contention); chunked updates instead of single massive transaction
- **"How do you recover from an unauthorized transfer?"** → Step-up auth prevents most; anomaly detection catches during; if succeeded: same-bank reversal fast; inter-bank requires bilateral bank process; fraud insurance covers losses
- **"How do you prevent rollback in banking?"** → You don't "rollback" — you write compensating transactions. Rollback violates audit integrity. If transfer succeeded and must be reversed: create a separate reversal transaction in ledger.
