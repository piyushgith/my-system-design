# 16 — Advanced Improvements & Architecture Critique: Double-Entry Ledger Service

---

## Objective

Critically evaluate the designed architecture, identify weaknesses, scaling limits, and tech debt risks. Propose advanced improvements for production-hardened and staff-level engineering conversations.

---

## Architecture Critique

### Weakness 1: Snapshot Contention (Hot Account Problem)

**What it is:** Every posting to the same account must CAS-update the `account_snapshots` row. At high concurrency on a single hot account (platform float), retries cascade into a write thundering herd.

**Current mitigation:** 3-attempt CAS retry with exponential backoff.

**True solution:** The snapshot design fundamentally couples the write path (posting) with balance state (snapshot). Virtual account sharding and async snapshot updates decouple these — but they add query complexity (balance query must aggregate across N virtual accounts).

**Why this is a real production problem:** Stripe, Brex, and Airbnb engineering blogs all describe variants of this problem. The float account in any payment platform is a hot row. Most teams discover it at 1,000+ TPS — not at design time.

---

### Weakness 2: Outbox Polling Latency

**What it is:** The outbox relay polls every 500ms. On average, events are delayed 250ms before Kafka publishing. For fraud detection, this means fraud scoring happens 250–750ms after a posting commits.

**Impact:** A sophisticated fraud pattern (card testing — many small transactions in quick succession) is detectable in real time. 250ms of lag means card testers have a window before the fraud system blocks them.

**Improvement:** Replace polling outbox with CDC (Debezium reading PostgreSQL WAL). WAL-based CDC delivers events in < 50ms.

**Trade-off:** Debezium requires a Kafka Connect cluster, WAL configuration changes (`wal_level=logical`), and careful schema management. Operational complexity is the cost.

---

### Weakness 3: Balance Snapshot Is Not Atomic with Journal Entry

**What it is:** The posting transaction commits: journal_entries INSERT + snapshot CAS UPDATE + outbox INSERT in one DB transaction. If the snapshot CAS UPDATE is in the same transaction, it increases transaction duration — holding locks longer. If moved async, snapshot can be momentarily stale.

**The fundamental tension:** Atomic snapshot (long transactions, lock contention) vs. async snapshot (brief stale window, more complex balance query).

**Current design:** In-transaction snapshot (atomic) — favors correctness over performance.

**Advanced design:** Use PostgreSQL's `LISTEN/NOTIFY` mechanism to asynchronously notify a snapshot worker after each posting commit. The snapshot worker applies deltas in micro-batches — decoupled from the posting transaction, but with < 10ms staleness.

---

### Weakness 4: Multi-Leg Cross-Shard ACID Loss

**What it is:** When the ledger is sharded by account_id, a 3-leg posting that touches accounts on different shards loses ACID atomicity. The only option is a distributed saga with compensating entries.

**Risk:** A 3-leg FX posting (source INR account, FX clearing account, destination USD account) could succeed for legs 1 and 2 but fail for leg 3 if shard 3 is unavailable. The compensating reversal must then succeed — or the books are imbalanced.

**Mitigation:**
- Design the chart of accounts so multi-leg postings land on the same shard (e.g., all platform accounts on shard 0, all customer accounts on shards 1–15)
- For true cross-shard postings: saga with a persistent workflow engine (Temporal) to track compensation state
- Accept: sharding is a correctness compromise. Do not shard until throughput absolutely requires it

---

### Weakness 5: No Period Close Enforcement in V1

**What it is:** V1 allows backdated entries with any `effective_at` in the past — even years back. A bug or mis-configured caller could post entries into closed accounting periods, invalidating historical reports.

**Risk:** Regulatory reports that were submitted to RBI/SEBI are now incorrect. Correction requires resubmission. This is a compliance incident.

**Solution (V2):** `accounting_periods` table with `start_date`, `end_date`, `status` (OPEN/CLOSED). Every posting validates `effective_at >= current_open_period_start`. Admin override requires multi-party approval (maker-checker on period-close bypass).

---

### Weakness 6: Balance Cache Eventual Consistency Window

**What it is:** After a posting commits, the balance cache is invalidated (DEL). The next read repopulates from the snapshot. Between invalidation and repopulation, concurrent readers may get different values from different snapshot reads (replica lag).

**Scenario:** Payment authorization reads balance from replica A (lag: 10ms behind). The most recent posting isn't visible yet — the balance appears higher than it should be. The payment is authorized against phantom balance.

**Mitigation:** For high-stakes balance reads (payment authorization, credit check), skip the cache and replica: read from PostgreSQL primary directly. Flag: `readBalanceOptions.mustBeFresh = true`.

**Trade-off:** Primary reads are slower (no cache benefit) and increase primary load. Reserve for financially-critical reads only.

---

## Advanced Improvement Proposals

### Improvement 1: Event-Sourced Balance (Pure Event Store)

Instead of maintaining `account_snapshots` as a materialized table, replace with an in-memory projection built from the event stream.

**Architecture:**
- Journal entries ARE the event store
- Balance is always `SUM(journal_entries)` since account creation — cached in Redis with a version counter
- On posting commit: emit a `BalanceDelta` event (not a state snapshot) — `{account_id, delta: +500000, version: 43}`
- Balance cache: `balance = previous_cached_balance + delta` — atomic Redis `INCRBY`

**Benefits:**
- Eliminates snapshot table and its CAS contention
- Balance updates via Redis `INCRBY` are atomic and O(1)
- No snapshot corruption possible — Redis value built incrementally

**Risks:**
- Redis `INCRBY` is atomic but if Redis restarts (without AOF), balance is lost — must rebuild from journal
- AOF on Redis for balance cache adds I/O overhead
- Redis balance becomes the hot read path — Redis availability is now critical for balance reads

---

### Improvement 2: CDC with Debezium for Sub-100ms Events

Replace the outbox polling relay with Debezium reading the PostgreSQL WAL:

**Architecture:**
- `wal_level=logical` in PostgreSQL config
- Debezium Postgres connector reads WAL changes to `outbox_events` table
- Publishes to Kafka in < 50ms of DB commit
- No relay job needed

**Benefits:**
- Sub-100ms fraud event delivery (vs 250–750ms with polling)
- Eliminates the relay job — one less moving part
- WAL-based delivery is exactly-once ordered (sequence guaranteed by WAL order)

**Operational cost:**
- Kafka Connect cluster required
- WAL replication slot must be managed (cannot fall too far behind — blocks WAL cleanup)
- Schema changes on `outbox_events` require connector restart

---

### Improvement 3: LMAX Disruptor for High-Frequency Posting

For the highest-throughput path (card authorization postings at 100,000+ TPS), replace the Spring MVC thread-per-request model with the LMAX Disruptor ring buffer pattern:

**Architecture:**
- All incoming posting requests placed on a pre-allocated ring buffer (lock-free)
- Single writer thread processes the ring buffer — no lock contention
- Batch multiple postings into one DB transaction (micro-batch commit: every 10ms or 100 entries)
- Dramatically reduces DB transaction overhead at ultra-high frequency

**Benefits:**
- Linear scalability — no lock contention on the hot path
- Batching reduces per-transaction overhead from ~5ms to ~0.05ms per posting

**When to use:** Only at 100,000+ TPS. This is HFT-level optimization. Most ledger systems never need it.

---

### Improvement 4: Temporal Workflows for Long-Running Saga

For multi-step postings that involve external systems (disbursement → bank transfer → loan activation), use Temporal workflow engine instead of manual saga coordination:

**Benefits:**
- Temporal guarantees exactly-once execution of each saga step
- Automatic retry with exponential backoff
- Workflow state is persisted — survives service restarts mid-saga
- Visual workflow history for debugging and audit

**Trade-off:** Temporal is additional infrastructure. Simpler sagas can be managed with Spring State Machine and the outbox pattern — reserve Temporal for truly long-running (hours to days) workflows.

---

## Scaling Limits

| Constraint | Limit | Resolution |
|---|---|---|
| Single PostgreSQL write throughput | ~5,000–10,000 posting TPS | Vertical scaling → connection pooling → sharding |
| Hot account CAS contention | 1,000 concurrent postings/account | Virtual sharding, async snapshot |
| Redis memory (balance cache) | 128 GB per cluster | Eviction policy → increase cluster → tiered caching |
| Kafka partition count | 200 partitions per broker | Increase broker count, partition reassignment |
| Journal_entries table size | ~8 GB/day growth | Archival pipeline → S3 → Athena for historical queries |
| Outbox relay latency | ~250ms average | CDC (Debezium) → < 50ms |

---

## Tech Debt Risks

| Debt | Risk | Mitigation |
|---|---|---|
| No period close enforcement | Backdated entries corrupt historical reports | V2 priority |
| Outbox polling | Fraud lag window | CDC upgrade |
| Single-currency only | Cannot expand to international | Multi-currency in V2 |
| Balance reads from replica | Stale balance on primary-replica lag | Force-primary flag for critical reads |
| Manual partition management | Missed partition = INSERT failure | `pg_partman` with alerting |
| No index on `outbox_events.posting_id` | Slow lookup during manual audit | Add index in V1 |

---

## Operational Burdens

| Burden | Cost | Who Owns |
|---|---|---|
| Monthly partition creation (pg_partman) | Automated, but needs monitoring | DBA / SRE |
| Archival pipeline to S3 | Quarterly manual trigger | Data Engineering |
| Snapshot rebuild on inconsistency | 1–4 hour procedure | Backend Engineering on-call |
| Kafka consumer group lag | Ongoing monitoring | SRE |
| PgBouncer tuning | Pool size must be adjusted as load grows | SRE |
| Schema registry version management | Each schema change requires registry update | Backend Engineering |

---

## What a Senior Interviewer Would Challenge

| Challenge | Expected Response |
|---|---|
| "Your CAS retries will fail at 10x load. Then what?" | Virtual account sharding + async snapshot — know the implementation detail |
| "How do you rebuild balance if journal_entries is corrupted?" | Journal is the source of truth — if corrupted, restore from backup + WAL replay. This is a disaster scenario, not a normal operation |
| "Why not use an immutable database (Datomic, Fauna) for the ledger?" | Valid alternative. Datomic's immutable fact model is isomorphic to event sourcing. Trade-off: niche technology, limited operational tooling, vendor risk. PostgreSQL's proven reliability and operational maturity is preferred for financial systems |
| "How would you prove to the auditors that no one modified the journal?" | (1) DB privileges: app role has no UPDATE/DELETE on journal_entries; (2) WAL-based audit trail (every DB operation logged in PostgreSQL WAL); (3) Cryptographic hash chain on journal entries — each entry includes a hash of the previous entry (similar to blockchain but simpler — a linked list of hashes). Auditors can verify the chain is unbroken |
| "What is the cost of the outbox pattern in terms of storage?" | One row per posting commit in `outbox_events`. At 5,000 posting RPS: 5,000 rows/sec × 500 bytes = 2.5 MB/sec. After `published=true`, batch-delete rows older than 7 days via a cleanup job. Steady-state size: 7 days × 2.5 MB/sec × 86,400 sec ≈ 1.5 TB — manageable with pruning |
