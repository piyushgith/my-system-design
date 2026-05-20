# 14 — Interview Discussion Points: Double-Entry Ledger Service

---

## Objective

Prepare for in-depth interviewer challenges on the double-entry ledger design. Covers fundamental concepts, scaling trade-offs, failure handling, and staff-level architecture discussions.

---

## Fundamental Concepts Interviewers Test

### Q1: Explain double-entry bookkeeping in one minute for a software engineer.

Every financial event has two equal and opposite effects. If you pay ₹500 for a coffee, two things happen simultaneously:
- Your bank account (ASSET) decreases by ₹500 — that's a CREDIT to your ASSET account
- The coffee shop's account (INCOME) increases by ₹500 — that's a CREDIT to their INCOME account

The rule: for every transaction, the sum of debits must exactly equal the sum of credits. This invariant means the books always balance — you can always verify that no money was created or destroyed.

In software terms: every `Posting` must contain at least two `JournalEntry` records, and `SUM(DEBIT amounts) == SUM(CREDIT amounts)` is enforced before any commit.

---

### Q2: Why not just use a transactions table with a `from_account` and `to_account`?

A two-column transaction table works for simple peer-to-peer transfers but breaks for:
- **Multi-leg entries**: A loan disbursement touches a loan receivable account, an interest income account, and a borrower wallet — 3+ legs in one atomic posting
- **Fees and charges**: A ₹500 payment might have ₹5 processing fee — 3 legs (payer debited ₹505, payee credited ₹500, fee income credited ₹5)
- **FX conversion**: 4 legs (source currency debit, FX clearing debit, FX clearing credit, destination currency credit)
- **Audit completeness**: Double-entry ensures the chart of accounts always balances — a two-column table doesn't enforce or enable this

Double-entry is the accounting standard because it makes errors visible — a missing leg or wrong amount makes the books unbalanced, which is immediately detectable.

---

### Q3: Walk me through what happens when a payment of ₹10,000 is made.

1. Payment service receives instruction from user
2. Payment service creates an idempotency key: `payment:pay_xyz789`
3. Payment service calls `POST /postings` with two legs:
   - CREDIT `acc_payer_savings` ₹10,000 (decrease payer's savings — credit reduces an ASSET)
   - DEBIT `acc_payee_current` ₹10,000 (increase payee's account — debit increases an ASSET)
4. Ledger validates sum(DEBIT) == sum(CREDIT) = ₹10,000 ✓
5. Ledger begins DB transaction
6. Inserts one row in `postings`, two rows in `journal_entries`
7. CAS updates `account_snapshots` for both accounts
8. Inserts `posting.completed` event in `outbox_events`
9. Commits transaction
10. Outbox relay publishes event to Kafka
11. Returns 201 Created with posting_id

---

## Common Interview Challenges

### Challenge 1: "How do you handle the 99.99% availability requirement?"

Multi-layer approach:
- **Application layer:** 10+ Kubernetes pods with HPA. `PodDisruptionBudget` ensures minimum capacity during node maintenance. `maxUnavailable: 0` in rolling updates
- **Database layer:** AWS RDS Multi-AZ — automatic failover < 30 seconds. RPO: zero (WAL streaming)
- **Cache layer:** Redis Cluster with 1 replica per primary — survives single-node failure
- **Kafka:** 3 brokers, replication factor 3, `min.insync.replicas=2` — survives single broker failure
- **Network:** Multi-AZ pod distribution — survives single AZ outage
- **Calculation:** 30-second DB failover per month = 0.0007% downtime. Remaining budget covers deployments and rare application issues. Total achievable: 99.99%

---

### Challenge 2: "What's your biggest bottleneck at 10x traffic?"

The `account_snapshots` row for hot accounts. Every posting must CAS-update the snapshot. At 50,000 postings/sec to the same account, the CAS retry loop saturates.

**Detection:** CAS retry rate > 20%.
**Solution sequence:**
1. Virtual account sharding (N sub-accounts for the hot account, periodic consolidation)
2. Async snapshot updates (snapshot updated in background, balance query = snapshot + delta journal entries)
3. If still insufficient: move to CQRS with an event-driven read model — the balance reader subscribes to `ledger.balance.changed` Kafka events and maintains its own materialized view in Redis, decoupled from the write path

---

### Challenge 3: "How do you prevent double charges?"

Three layers of defense:

1. **Caller-side:** Caller generates a stable `idempotency_key` (`payment:pay_xyz789`) before sending. On retry, same key is sent
2. **Cache layer:** Redis lookup by idempotency_key — HIT returns existing posting without hitting DB
3. **Database constraint:** UNIQUE on `postings.idempotency_key` — even if Redis is unavailable and two concurrent requests slip through, only one INSERT will succeed. The other gets a constraint violation and returns the existing posting

The key insight: idempotency is not just a performance optimization — it is a financial correctness guarantee.

---

### Challenge 4: "How do you handle a multi-currency payment (INR to USD)?"

A 4-leg posting models the FX transaction:

```
Leg 1: CREDIT acc_payer_inr    ₹82,000 INR  (debit payer's INR account)
Leg 2: DEBIT  acc_fx_inr       ₹82,000 INR  (credit FX pool INR side)
Leg 3: CREDIT acc_fx_usd       $1,000 USD   (debit FX pool USD side)
Leg 4: DEBIT  acc_payee_usd    $1,000 USD   (credit payee's USD account)
```

Invariant check is per-currency:
- INR: DEBIT ₹82,000 == CREDIT ₹82,000 ✓
- USD: DEBIT $1,000 == CREDIT $1,000 ✓

The FX rate (82 INR per USD) is applied by the Payment service before constructing the posting request. The ledger does not do currency conversion — it only records the conversion as accounting entries.

---

### Challenge 5: "How do you query balance for regulatory reporting as of December 31?"

Point-in-time balance query:

```sql
SELECT
    SUM(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END) as balance,
    currency
FROM journal_entries
WHERE account_id = :accountId
  AND effective_at <= '2023-12-31 23:59:59.999'
GROUP BY currency;
```

**Performance concern:** This scans all journal entries since account creation, potentially millions of rows.

**Optimization:**
1. Find the most recent snapshot with `updated_at <= '2023-12-31 23:59:59'`
2. Start from snapshot balance + SUM of entries since snapshot watermark up to `2023-12-31`
3. Compound index: `(account_id, effective_at DESC, entry_id)` enables efficient range scan

---

## Scaling Evolution Questions

### "Walk me through scaling from Day 1 to 50,000 posting RPS."

| Phase | Throughput | Architecture |
|---|---|---|
| Day 1 | 100 RPS | Single Spring Boot pod, single PostgreSQL, Redis |
| Month 6 | 1,000 RPS | 5 pods, read replicas, PgBouncer |
| Year 1 | 5,000 RPS | 10 pods, connection pooling tuned, hot account identified and sharded |
| Year 2 | 20,000 RPS | CQRS split (write service / read service), snapshot async update |
| Year 3 | 50,000 RPS | Account-range sharding, GL aggregation in ClickHouse, multi-region |

The key is not over-engineering on Day 1. A single PostgreSQL can handle 5,000+ TPS for simple INSERTs. Most ledger systems never reach the point where sharding is necessary.

---

## What Would Break First Analysis

| Load Level | First Failure | Second Failure |
|---|---|---|
| 2x expected load | Snapshot CAS retry rate spikes | Balance cache evictions |
| 5x expected load | PgBouncer connection queue full | Outbox relay falls behind |
| 10x expected load | PostgreSQL primary CPU saturates | Kafka consumer lag grows |
| 100x expected load | DB primary can't handle writes | Need sharding — ACID guarantee weakens |

---

## Senior vs Staff Engineer Discussion Depth

### Senior Engineer Level

- Knows the double-entry invariant and why it matters
- Can design the journal_entries + postings schema
- Understands idempotency via UNIQUE constraint
- Knows why floating point is wrong for money
- Can discuss optimistic locking (CAS) vs pessimistic locking

### Staff Engineer Level

- Discusses virtual account sharding for hot accounts (not just idempotency)
- Proposes Outbox pattern proactively — knows that direct Kafka writes in transactions are broken
- Discusses period-close enforcement and its impact on backdated entries
- Can explain the difference between the Posting aggregate (write side) and AccountSnapshot (read projection)
- Raises the GL aggregation problem at scale: how does a trial balance work when the journal is sharded across 16 DB instances?
- Discusses the accounting period rollup problem: OLAP on the event stream vs real-time journal queries

### Principal Engineer Level

- Designs the balance sharding key to minimize cross-shard multi-leg postings
- Proposes Saga-based distributed postings with compensating entries for cross-shard atomicity
- Discusses regulatory implications of point-in-time balance: what does "balance at period end" mean when entries are still being processed (T+1 settlement)?
- Proposes event-sourced ledger where the event stream IS the journal, and balances are always projections — never stored state

---

## Common Mistakes Candidates Make

| Mistake | Why It's Wrong | Correct Approach |
|---|---|---|
| Storing balance in the Account table | Update race condition on concurrent postings | Snapshot table + CAS |
| Using floating point for money | IEEE 754 rounding drift | Integer arithmetic (paise/cents) |
| Only one journal entry per transaction | Breaks double-entry — can't validate invariant | Minimum two legs per posting |
| Soft-deleting journal entries | Breaks audit trail | Reversal entries only |
| Calling Kafka inside the DB transaction | Atomic commit impossible across two systems | Outbox pattern |
| Not including effective_at in schema | Can't do point-in-time queries | Separate effective_at and created_at |
| Skipping idempotency | Payment retries = double charges | Idempotency key with UNIQUE constraint |
| Storing account balance as NUMERIC in DB | Slower than BIGINT | Store as BIGINT (smallest currency unit) |

---

## What a Senior Interviewer Will Challenge

1. **"Your snapshot CAS approach — what happens at 50K TPS to the float account?"** Answer with virtual account sharding
2. **"How do you rebuild the balance from scratch if the snapshot table is dropped?"** Replay all journal entries — the journal is the source of truth, snapshot is derived
3. **"Why is your outbox polling-based and not CDC-based?"** Polling adds 500ms latency; CDC (Debezium on WAL) gives sub-100ms. Acceptable trade-off for V1; CDC is the V2 optimization
4. **"What does 'idempotent' mean for your Kafka consumers?"** If the same `posting.completed` event is delivered twice (at-least-once delivery), the consumer must process it exactly once — by deduplicating on `event_id` before writing to their data store
5. **"How does your design handle the 4-year accounting requirement if you're partitioning and archiving?"** The archived partitions are queryable via Athena on S3. The reconciliation and audit systems have access to both live PostgreSQL and the Athena archive through a query federation layer
