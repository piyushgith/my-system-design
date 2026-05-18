# 07 — Scaling Strategy: Payment Gateway / Wallet System

---

## Objective

Define scaling approach for a payment gateway handling high-volume financial transactions — with focus on throughput, latency, consistency, and zero data loss under peak load.

---

## Scaling Tiers

| Tier | Transactions/Day | Peak TPS | Architecture |
|---|---|---|---|
| Startup | 10K/day | 5 TPS | Monolith, single Postgres |
| Growth | 500K/day | 200 TPS | Separate payment service, Redis, read replicas |
| Scale | 5M/day | 2,000 TPS | Kafka async, partitioned DB, multi-AZ |
| FAANG | 100M+/day | 50,000 TPS | Multi-region active-active, sharded ledger |

---

## Read vs Write Profile

Payment systems are write-heavy on the critical path:

| Operation | Type | Frequency | Latency SLA |
|---|---|---|---|
| Payment initiation | Write (strong consistency) | High | < 500ms |
| Ledger debit/credit | Write (ACID) | High | < 100ms (internal) |
| Balance check | Read | Very High | < 50ms |
| Transaction history | Read | High | < 200ms |
| Reconciliation | Read (batch) | Low | No SLA (offline) |
| Fraud check | Read + Write | Per transaction | < 100ms |

---

## The Core Scaling Challenge

Unlike e-commerce, payment scaling is constrained by ACID guarantees:

- You cannot cache a balance and risk serving stale data → double spend
- You cannot skip transaction isolation → race conditions → lost money
- You cannot use eventual consistency for debit/credit → money appears/disappears

**This means**: some scaling techniques available to other systems are unavailable here. Design around this constraint.

---

## Scaling Lever 1: Read Isolation

Balance reads are frequent and don't need to be on the write primary:

- Write path: Postgres primary (ACID, strict)
- Balance reads: Redis cache (1-5s staleness acceptable for display)
- Balance at transaction time: Redis atomic check (real-time, not cached)
- Transaction history: Postgres read replica

**Critical distinction**:
- Showing balance on dashboard: Redis cache OK (display purposes)
- Deducting balance during payment: Redis atomic operation, reconciled to Postgres

---

## Scaling Lever 2: Ledger Partitioning

At high scale, single ledger table becomes bottleneck.

**Partition by account_id:**
```sql
CREATE TABLE ledger_entries PARTITION BY HASH (account_id);
CREATE TABLE ledger_entries_0 PARTITION OF ledger_entries FOR VALUES WITH (modulus 8, remainder 0);
-- ... 8 partitions
```

- Transactions between accounts on same partition: fast local operation
- Transactions between accounts on different partitions: distributed transaction (more expensive)
- Partition key choice matters: by user_id concentrates user's transactions; by currency concentrates currency-based queries

**At extreme scale**: separate Postgres instances per partition (actual DB sharding).

---

## Scaling Lever 3: Async Payment Processing

Not every payment step needs to be synchronous from user perspective:

```
Synchronous (user waits):
  1. Idempotency check
  2. Fraud pre-check (fast rule-based)
  3. Debit from sender account (atomic)
  4. Return "payment accepted" to user

Asynchronous (background):
  5. Credit to receiver account
  6. Settlement processing
  7. Notification dispatch
  8. Analytics events
  9. ML fraud model scoring (post-hoc review)
```

User sees instant response. Risk: step 5-9 delayed but sender already debited. Reconciliation ensures credit happens.

---

## Scaling Lever 4: Horizontal API Scaling

Payment API servers are stateless:

- No in-memory state (all state in Postgres + Redis)
- Kubernetes HPA: scale on CPU (payment processing is CPU-intensive for crypto/hashing)
- Connection pooling via PgBouncer (critical — payment service has many concurrent connections)
- Each pod validates JWT, checks idempotency, writes to ledger

**Stateless is non-negotiable**: any statefulness in payment API = correctness risk.

---

## Scaling Lever 5: Kafka for Downstream Decoupling

Payment events fan out to many consumers:

```
PaymentSucceeded event:
  → notification-service (email/SMS receipt)
  → analytics-service (revenue tracking)
  → merchant-service (update merchant balance)
  → settlement-service (batch settlement processing)
  → fraud-service (post-transaction risk scoring)
```

Kafka decouples: payment processing speed not affected by notification slowness.

---

## Wallet Scaling

Wallet balance = most frequently read data in system.

**Strategy**: Redis as fast read layer, Postgres as durable truth.

```
Balance read:
  1. Read from Redis: O(1), < 1ms
  2. If Redis miss: read from Postgres, populate Redis

Balance debit (payment):
  1. Lua script in Redis: DECRBY balance:{walletId} {amount} — atomic
  2. Check result >= 0 (sufficient funds)
  3. Async persist to Postgres via outbox pattern
  4. If Redis failure: fall back to Postgres SELECT FOR UPDATE
```

**Risk**: Redis loses data before Postgres write completes. Mitigation:
- Redis AOF persistence (fsync every second, max 1s data loss)
- Redis → Postgres write is idempotent (deduplication key)
- Reconciliation job every minute: compare Redis balance to Postgres sum of ledger entries

---

## Rate Limiting for Payments

Payments are high-value targets for fraud and abuse:

| API | Limit | Window | Enforcement |
|---|---|---|---|
| Payment initiation | 10 per user | per minute | Redis token bucket |
| Wallet top-up | 5 per user | per hour | Redis |
| Bulk payments | 1,000 per merchant | per day | Redis + DB check |
| API key (merchant) | 10,000 req | per minute | Redis |

Hard limits enforced at API gateway before reaching payment service.

---

## Database Connection Management

Payment service under load: hundreds of concurrent transactions → DB connection exhaustion.

**PgBouncer configuration:**
- Pool mode: transaction (not session) — connection released after each transaction
- Max DB connections: 100 (Postgres default max_connections = 200, shared with replicas)
- Max client connections: 2000 (pods × threads)
- This allows 2000 concurrent API requests multiplexed over 100 DB connections

**Connection pool exhaustion = payment failures.** Monitor pool utilization; alert at 80%.

---

## Multi-Region Strategy

Payment systems must be available globally with low latency:

**Phase 1 (< 1M TPS)**: Single region, multi-AZ
- Postgres Multi-AZ (synchronous replication)
- Redis Cluster across 3 AZs
- API pods across 3 AZs

**Phase 2 (> 1M TPS)**: Active-passive multi-region
- Primary region: all writes
- Secondary region: read-only replica, ready for failover
- RTO: < 60s (automatic failover via Route 53)
- RPO: < 5s (async replication lag)

**Phase 3 (FAANG)**: Active-active multi-region
- Each region handles local currency transactions
- Cross-region transfers via specialized reconciliation
- Extreme complexity — only justified at Stripe/PayPal scale

---

## Performance Bottlenecks

| Bottleneck | Detection | Fix |
|---|---|---|
| Ledger table lock contention | High lock wait times in DB | Partition ledger by account_id |
| SELECT FOR UPDATE serialization | Long transaction queue | Optimistic locking + retry |
| DB connection pool exhaustion | PgBouncer wait time spike | Increase pool; reduce transaction duration |
| Redis DECR under contention | Redis CPU spike | Redis is single-threaded; use Cluster |
| Payment gateway API timeout | High external call latency | Circuit breaker; async payment flow |
| Fraud check blocking payment | High fraud service latency | Async post-transaction fraud check for low-risk |

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Postgres over NoSQL for ledger | ACID guarantees | Write throughput ceiling |
| Redis for balance display | Sub-millisecond reads | Staleness window, Redis failure risk |
| Async downstream processing | Payment API speed | Credit may be delayed |
| PgBouncer transaction mode | High connection efficiency | Session-level features unavailable |
| Single-region start | Operational simplicity | Higher latency for distant users |

---

## Interview Discussion Points

- **"Why not use NoSQL for the ledger?"** → ACID is mandatory; double-entry requires atomic debit + credit; no NoSQL gives this without distributed transaction complexity worse than Postgres
- **"How do you scale to 50K TPS?"** → Ledger partitioning + Redis for balance reads + Kafka for downstream decoupling + connection pooling; at true FAANG scale, shard Postgres by account range
- **"What happens if Redis loses the balance data?"** → Redis AOF persistence limits loss to 1s; reconciliation job rebuilds Redis balance from Postgres ledger entries; slight delay but no permanent data loss
- **"How do you handle global payments?"** → Active-passive multi-region; writes to primary; local reads from replica; cross-region failover via Route 53
