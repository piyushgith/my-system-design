# 14 — Interview Discussion Points: Banking Core System

---

## Objective

Prepare for Taking/fintech interview discussions on banking core system design — covering the hardest problems: regulatory compliance, maker-checker patterns, audit systems, distributed transaction handling, and the tradeoffs unique to financial institutions.

---

## Expected Interviewer Opening Questions

- "Design a core banking system"
- "Design a transaction processing system for a bank"
- "How would you implement a maker-checker approval system?"
- "Design an audit trail system for a financial application"
- "How would you handle concurrent fund transfers in a banking system?"

**Critical first question**: "Are we designing internet banking (retail), corporate banking, or a core banking system (CBS)? The scale and complexity differ significantly."

---

## Hardest Questions and Strong Answers

### Q: How do you handle concurrent transfers that could overdraft an account?

Shallow answer: "Use a lock on the account row."

**Strong answer**:
> Two approaches. First: Pessimistic locking — `SELECT FOR UPDATE` on the account row before debit. Simple and correct. Works at low-to-medium concurrency. Downside: serializes all transfers from same account; lock contention under high volume.
>
> Second: Optimistic locking — read current balance + version number, attempt debit, check version hasn't changed. If changed, retry. Works well when contention is low.
>
> For banking at scale: pessimistic locking per account (not per table). One account's transfers serialize; different accounts proceed in parallel. Since most accounts have low individual transfer frequency, this is acceptable. At extreme scale (corporate treasury accounts with 1000s of transfers/hour), partition the lock at sub-account level.

### Q: Explain your maker-checker implementation in detail.

**Strong answer**:
> Maker-checker (4-eyes principle) ensures no single person can both initiate and approve a financial transaction.
>
> Implementation: Separate the request creation from the execution. Maker creates a `maker_checker_request` record (status: PENDING) but does NOT execute the actual transaction. This record contains: type, amount, from_account, to_account, maker_id, created_at.
>
> Checker views pending queue, reviews the request details, clicks Approve. System validates: (1) checker is not the same as maker, (2) checker has authority for this amount, (3) request hasn't expired or been cancelled. On approval: status → APPROVED, checker_id + approved_at recorded.
>
> Execution step: APPROVED requests trigger the actual transfer — either by Kafka consumer or a scheduled job. This decouples approval from execution.
>
> Audit: every state transition (SUBMITTED → APPROVED → EXECUTED) is logged with who did what and when. Cryptographic signature on the approval: checker signs the transaction hash with their private key (YubiKey). Impossible to forge or tamper post-approval.

### Q: How do you ensure the audit log is tamper-proof?

**Strong answer**:
> Three layers. First: append-only table in Postgres — no DELETE or UPDATE permissions granted on audit table to any application role. Only INSERT is allowed.
>
> Second: hash chain — each audit log entry contains SHA-256 hash of the previous entry. Any modification to a past entry invalidates the hash chain forward. Weekly verification job runs the chain and alerts on any break.
>
> Third: S3 Object Lock (WORM — Write Once Read Many) — archived audit logs uploaded to S3 with Object Lock. Retention period: 10 years. AWS enforces this at the storage level — not even root user can delete. Physical separation from the application.
>
> Why not just rely on access control? Insider threat. An administrator with DB access could delete records without hash chain detection if it weren't independently verified. Defense in depth.

### Q: How does EOD processing work in a banking system?

**Strong answer**:
> End-of-day (EOD) is one of the most complex operations in banking. It must complete within the overnight window (11 PM to 6 AM) and must be fault-tolerant.
>
> Key steps: (1) Interest accrual — calculate daily interest for savings/FD accounts. At 50M accounts, this is distributed: Spark job partitioned by account range, runs against read replica. (2) EMI deduction — for accounts with loan EMI due today, debit savings account, credit loan account. (3) TDS deduction — quarterly, on qualifying interest income. (4) Statement generation — triggered but async (generated lazily on first access). (5) Regulatory reports — CTR, aggregate reports compiled from day's events.
>
> Kafka orchestration: scheduler emits `batch.eod-triggered` event; batch consumers process in parallel; completion events fed back; orchestrator monitors for completion within window. Alert at 7 AM if not complete.
>
> What happens if batch runs over? Alert at 7 AM. Prioritize: complete salary account interest first (customer-visible), then savings, then FD. Delay non-customer-visible processing (analytics, secondary reports).

---

## Tradeoff Discussions

### Modular Monolith vs Microservices for Banking

| Factor | Modular Monolith | Microservices |
|---|---|---|
| Cross-module transactions | Local transaction (simple) | Saga pattern (complex) |
| Team size | Suits 5-20 engineers | Suits 20+ per service |
| Compliance | Easier to audit one system | Harder to audit distributed system |
| Deployment risk | Larger blast radius | Smaller per service |
| Data consistency | Strong (single DB) | Eventual (distributed) |
| Operational complexity | Low | High |

**Banking recommendation**: Modular monolith first. Banking needs strong consistency for transfers. Distributed transactions across microservices require Saga patterns that are complex to audit and debug in a regulated environment. Extract services only when: regulatory requirements mandate isolation (e.g., AML as separate system for auditor access), or scale truly requires it.

### Synchronous vs Asynchronous Approval (Maker-Checker)

| Sync Approval | Async Approval |
|---|---|
| Simple state machine | Event-driven state machine |
| Blocking API call waits for checker | Maker submits; checker notified later |
| Checker must be online | Checker reviews in own time |
| Not suitable for high-volume | Scales to thousands of pending approvals |
| Low complexity | Medium complexity |

**Recommendation**: Async. Checkers have their own schedule. Synchronous approval is only feasible for very-high-priority, time-sensitive transactions (e.g., RTGS same-day settlement).

---

## Senior Engineer Discussion Points

### Reconciliation Architecture

The reconciliation process is what catches every bug that slips through:

- **Account reconciliation**: stored balance = sum of all ledger entries for account. Run hourly.
- **Day-end reconciliation**: total transactions today = sum of debits = sum of credits. Verify invariant.
- **Bank statement reconciliation**: our records vs bank's SWIFT/NEFT settlement file.
- **GL reconciliation**: product-level (savings, current, loans) totals match general ledger.

Each reconciliation tier catches different classes of bugs. Multi-tier reconciliation is how banking achieves the "every rupee is accounted for" guarantee.

### Interest Calculation Correctness

Interest calculation seems simple but has many edge cases:

- Leap year: 366 days in denominator, not 365
- Month boundary: if account opened mid-month, prorate correctly
- Rate changes: apply old rate for days before rate change, new rate after
- Tax (TDS): deduct 10% on interest credited > ₹10,000/year (cumulative tracking)
- Compound vs simple: savings accounts use simple; FDs use compound (quarterly)

These edge cases are where banking bugs hide. Unit tests must cover all combinations.

### SWIFT Integration

For international transfers (SWIFT):

- Bank must be SWIFT BIC member
- Message types: MT103 (customer credit transfer), MT202 (financial institution transfer)
- Compliance: OFAC sanctions screening before every outgoing SWIFT
- Correspondent banking: bank may use a correspondent bank for currencies it doesn't hold directly
- Cut-off times: SWIFT messages have cut-off times; late submissions go next business day

---

## Staff Engineer Discussion Points

### Conway's Law in Banking Architecture

Team structure drives banking architecture:
- Retail banking team → Savings, Current account services
- Loans team → Loan origination, EMI, collections services
- Trade finance team → LC, BG, export collection services
- Treasury team → FX, money market, bond services

Each team becomes a potential service boundary. Before building microservices, ask: "Does each team have clear ownership?" If teams share a service: monolith. If team needs autonomy: separate service.

### Legacy Core Banking System Integration

Most banks have: legacy CBS (Temenos, Finacle, Infosys Finacle) + modern wrapper:

```
Modern API Gateway (our system)
    │
    ↓
Legacy CBS Integration Layer (adapter pattern)
    │
    ↓
Temenos T24 / Finacle CBS
```

Migration strategy: Strangler Fig Pattern
- New features built in modern system
- Legacy CBS wrapped by adapter
- Gradually move accounts from CBS to modern system
- CBS becomes eventual read-only archive

This takes 5-10 years at a large bank. The strangler fig is the only practical approach.

### Regulatory Technology (RegTech)

Staff-level discussion: compliance as code.

- AML rules engine: configurable rules in DB (not hardcoded); compliance team updates via portal
- KYC risk scoring: ML model vs rule-based; explainability required for regulatory audit
- Regulatory reporting: RBI changes report formats; format must be configurable, not hardcoded
- FATCA/CRS: US and international tax reporting for foreign nationals — separate module

RegTech is a competitive advantage. Banks that automate compliance spend less on operations and respond faster to regulatory changes.

---

## Common Mistakes Interviewers Watch For

| Mistake | Why It's Wrong |
|---|---|
| Rollback on failed transfer | Cannot rollback in banking; use compensating transactions |
| Float for money amounts | Precision errors; always use long (paise) |
| Maker approves their own request | Violates 4-eyes principle; system must enforce |
| Eventual consistency for transfer | Cannot have eventual consistency for fund movement |
| No audit log for staff actions | Regulatory violation; RBI can penalize |
| Balance caching without disclaimer | Showing stale balance without disclosure = dispute liability |
| Microservices for banking from day 1 | Distributed transactions + compliance across services = nightmare |
| Missing idempotency on transfer API | Double debit on network retry = financial loss |

---

## "What Would Break First?" Analysis

| Load Multiplier | First Failure | Fix |
|---|---|---|
| 2x | Postgres connection pool exhaustion | PgBouncer |
| 5x | Read query load on primary (balance inquiries) | Read replicas + cache |
| 10x | Transfer write TPS on primary | Partition ledger by account_id |
| 50x | EOD batch time exceeds window | Distributed Spark batch |
| 100x | Single CBS cannot handle all products | Multi-core architecture (separate DB per product) |
| 500x | Geographic latency for global users | Multi-region with local acquiring |

---

## Numbers to Know

| Metric | Value |
|---|---|
| RBI maximum idle session timeout | 15 minutes |
| CTR filing deadline | 7 days from transaction |
| STR filing deadline | 7 days from suspicion identification |
| NEFT settlement batches (2024) | 24x7, every 30 minutes |
| RTGS operating hours | 7:00 AM to 6:00 PM |
| RBI mandate for internet banking availability | 24x7 |
| Cheque clearing (CTS) | T+1 business day |
| Retail transfer daily limit | Up to bank policy (typically ₹2L-₹10L for retail) |
| RTGS minimum amount | ₹2 lakh |
| NEFT maximum | No limit |

---

## Interview Tips (Banking-Specific)

1. **Mention double-entry upfront** — shows you understand the fundamental invariant
2. **Distinguish maker vs checker** — interviewers probe this; know the rules well
3. **Know NEFT vs RTGS vs IMPS** — different rails, different settlement, different use cases
4. **Use "compensating transaction" not "rollback"** — shows financial systems thinking
5. **Mention reconciliation** — what catches all the bugs; most candidates miss this
6. **Reference RBI regulations** — CTR/STR deadlines, session timeout, 24x7 mandate
7. **Justify modular monolith** — banking interviewers often accept this over microservices if justified
8. **Know DDD**: maker-checker bounded context, ledger bounded context, notification bounded context
