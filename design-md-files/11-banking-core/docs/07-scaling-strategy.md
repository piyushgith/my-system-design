# 07 — Scaling Strategy: Banking Core System

---

## Objective

Define scaling approach for a banking core system where correctness and compliance always take precedence over throughput, and where the scaling model differs fundamentally from typical web-scale systems.

---

## Banking Scaling is Different

Banking systems scale differently from e-commerce or social media:

| Dimension | E-Commerce | Banking Core |
|---|---|---|
| Primary constraint | Read throughput | Write correctness |
| Cache strategy | Aggressive caching | Limited (regulatory accuracy) |
| Consistency model | Eventual OK | Strong required |
| Scale target | 100K RPS | 10K-100K TPS (transactions, not HTTP) |
| Downtime tolerance | Minutes | Zero (24x7 banking) |
| Data loss tolerance | Acceptable with notice | Zero |

---

## Scaling Tiers

| Tier | Accounts | TPS | Architecture |
|---|---|---|---|
| Startup (neobank) | 100K | 100 | Monolith, single Postgres, Redis |
| Regional bank | 5M | 2,000 | Modular, Postgres clusters, caching |
| Large bank | 50M | 20,000 | Multiple cores, Kafka, read replicas |
| Global bank | 500M+ | 200,000+ | Multi-region, sharded, real-time core |

---

## Read vs Write Profile

| Operation | Type | Frequency | Scaling Approach |
|---|---|---|---|
| Balance inquiry | Read | Very high | Redis cache (short TTL) |
| Transaction history | Read | High | Postgres read replica |
| Account statement | Read | Medium | Cached + async generation |
| Fund transfer | Write | High | Postgres primary, ACID |
| Interest calculation | Batch | Daily | Offline processing |
| Loan EMI deduction | Batch | Monthly | Scheduled job |
| Regulatory reporting | Batch | Daily/Monthly | Read replica |

---

## Scaling Lever 1: Read Replica Separation

Most banking queries are reads:

- Balance inquiry: highest frequency; 60% of all queries
- Transaction history: 25% of queries
- Account details: 10% of queries
- Write transactions: 5% of queries but most critical

**Implementation**:
```
Postgres Primary  ← All writes (fund transfers, account updates)
Postgres Replica 1 ← Balance reads, account detail reads
Postgres Replica 2 ← Transaction history, statement queries
Postgres Replica 3 ← Analytics, regulatory reporting (long-running queries)
```

Replica lag: < 100ms acceptable for balance display. For balance at transaction time: always read from primary.

---

## Scaling Lever 2: Balance Cache (Redis)

Balance inquiry is the most frequent banking API call:

```
GET balance/{accountId}
  → Redis cache: GET balance:{accountId}
  → If hit: return (< 1ms)
  → If miss: read from Postgres, cache with TTL 10s
  → On any transaction: invalidate cache (event-driven)
```

Caveats:
- Cache is for display only (mobile app home screen balance)
- Loan disbursement, fund transfers always read from Postgres
- Core banking systems may prohibit balance caching for regulatory reasons — evaluate per bank's compliance stance

---

## Scaling Lever 3: CQRS for Account History

Account statement queries are expensive on transactional DB:

- Write model: append-only transaction table (ACID, normalized)
- Read model: pre-aggregated account summary, denormalized statement view

```
Transaction written → Kafka event → Statement projector consumer
  → Updates: account_monthly_summary (running balance, credit/debit totals)
  → Cache: last 30 days of transactions in Redis sorted set

Statement API:
  → < 30 days: Redis (sub-second)
  → > 30 days: Postgres read replica (bulk fetch)
  → > 7 years: S3 archive (async generation + email)
```

---

## Scaling Lever 4: Maker-Checker Async Decoupling

High-volume maker-checker (approval workflows) block on human approval:

- Maker submits request → goes to Kafka queue
- Checker polls queue → approves/rejects
- No blocking synchronous call chain waiting for human action
- Scale: thousands of pending approvals without DB lock contention

```
maker_checker_requests table:
  - Status: PENDING / APPROVED / REJECTED / EXECUTED
  - Only APPROVED requests are executed (triggering actual DB changes)
  - PENDING requests don't lock any accounts
```

---

## Scaling Lever 5: Batch Processing Separation

Banking batch jobs (EOD, EOM) are massive:

| Batch | Volume | Window |
|---|---|---|
| Interest calculation | All savings accounts | 11 PM - 3 AM |
| EMI deduction | All loan accounts with due date | 3 AM - 6 AM |
| TDS deduction | Qualifying interest accounts | Monthly |
| Statement generation | All accounts | 1st-3rd of month |
| AML monitoring | All transactions | Continuous, micro-batch |

**Isolation strategy**:
- Batch jobs run against separate read replica (never primary)
- Writes from batch: batched and committed in chunks (not one massive transaction)
- Batch window: monitored; alert if batch doesn't complete before branch opening (9 AM)

---

## Rate Limiting for Banking APIs

| API | Limit | Window |
|---|---|---|
| Balance inquiry | 30 req | per minute per user |
| Fund transfer (retail) | 10 req | per minute per user |
| NEFT/RTGS initiation | 5 req | per minute per user |
| Account statement | 10 req | per hour per user |
| Maker-checker submission | 100 req | per minute per maker role |
| Admin APIs | 1,000 req | per minute per IP allowlist |

---

## Database Scaling

### Account Data Partitioning

At 50M+ accounts, account table partitioning:

```sql
-- Partition by account number range (hash)
CREATE TABLE accounts PARTITION BY HASH (account_number);
CREATE TABLE accounts_0 PARTITION OF accounts FOR VALUES WITH (modulus 8, remainder 0);
-- ... 8 partitions

-- Partition transactions by date (for archival)
CREATE TABLE transactions PARTITION BY RANGE (transaction_date);
CREATE TABLE transactions_2024 PARTITION OF transactions FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
```

Date partitioning: older partitions moved to cheaper storage or S3 archival.

### Connection Pooling

PgBouncer critical for banking:
- 10,000 concurrent users × 5 threads each = 50,000 potential connections
- Postgres max: 500 connections
- PgBouncer: 50,000 client connections → 500 DB connections (transaction mode)

### Multi-Core Banking Architecture (Large Banks)

At scale, a single Postgres can't handle all products:

```
Core Banking CBS → Account Core (Postgres Cluster 1)
                → Loan Core (Postgres Cluster 2)
                → Fixed Deposit Core (Postgres Cluster 3)
                → Trade Finance Core (Postgres Cluster 4)
```

Inter-product transfers (savings → loan repayment) via internal transaction bus (Kafka).

---

## Performance Bottlenecks

| Bottleneck | Detection | Fix |
|---|---|---|
| Account lock contention | High lock wait time | Optimistic locking for balance update |
| Large transaction history query | Slow query log > 2s | Partition + archive old data |
| Batch job overrun | Batch not complete by 9 AM | Parallelize batch, add replica capacity |
| Interest calculation | CPU spike at EOD | Distributed batch (Spark) |
| Maker-checker approval queue | Queue depth growing | Add checker capacity, priority routing |
| Full-text search on transactions | High DB CPU | Elasticsearch for transaction search |

---

## Zero-Downtime Operations

Banking cannot go offline. Scaling operations must be zero-downtime:

| Operation | Downtime Approach |
|---|---|
| Add read replica | Zero downtime (hot standby) |
| Postgres minor version upgrade | Rolling restart with failover |
| Postgres major version upgrade | Blue-green DB upgrade (parallel upgrade) |
| Add partition to table | `ATTACH PARTITION` online in Postgres 14+ |
| Create index | `CREATE INDEX CONCURRENTLY` |
| Schema column add | Add nullable column — no table lock |
| Schema column drop | Expand-contract: stop using → then drop |

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Postgres over NoSQL | ACID, SQL, regulatory familiar | Write throughput ceiling |
| Strong consistency on transfers | No money disappears | Lower throughput, higher latency |
| Batch separation | No OLAP on OLTP | Batch infrastructure to manage |
| Modular monolith | Simpler ops, strong consistency | Single deployment unit limits team independence |
| Redis balance cache | High read throughput | Compliance risk if used for transactions |

---

## Interview Discussion Points

- **"How does banking scaling differ from social media?"** → Banking is write-correctness-bound; social media is read-throughput-bound. Different scaling levers: bank scales writes carefully; social media scales reads aggressively
- **"Can you use eventual consistency in banking?"** → For display (balance on home screen): yes, brief staleness acceptable. For transactions: no. For regulatory reports: no. Categorize which queries need what consistency.
- **"How do you scale interest calculation for 50M accounts?"** → Distributed batch: Apache Spark on read replica. Shard by account range. 50M accounts × simple interest formula = parallelizable. Write results back in chunked batches.
- **"What breaks first under 10x load?"** → Postgres connection pool → PgBouncer. Then read replica lag → add more replicas. Then write TPS → partition accounts. Rarely: actual core banking logic is the bottleneck.
