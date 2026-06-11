# 15 — Implementation Roadmap: Double-Entry Ledger Service

---

## Objective

Define the phased implementation plan from MVP through production-grade scale. Each phase delivers a working, deployable increment. Financial systems require extra rigor at each phase — correctness before performance.

---

## MVP (Weeks 1–6): Core Ledger Foundation

### Goal: Working double-entry ledger that upstream systems can call

### Features

- Chart of accounts management (CRUD for accounts with type validation)
- Single-currency posting API with debit=credit validation
- Idempotency via Redis + DB UNIQUE constraint
- Account balance via `SUM(journal_entries)` (no snapshot yet)
- Basic REST API (no gRPC in MVP)
- Reversal posting support
- Integration tests using TestContainers (PostgreSQL, Redis)

### Architecture

- Single Spring Boot application
- Single PostgreSQL instance (no replication)
- Single Redis instance (no cluster)
- No Kafka — postings are synchronous only
- No partitioning (single `journal_entries` table)

### Infra

- Docker Compose for local development
- Single Kubernetes deployment in `dev` namespace
- No CI/CD automation (manual deploy from dev machine)

### Risks

- Balance computation via full table scan is slow at scale — accepted in MVP
- No snapshot — every balance read hits the DB with `SUM()`
- No event publishing — downstream systems cannot consume ledger events yet

### Team

- 1–2 backend engineers

---

## V1 (Weeks 7–16): Production-Ready Core

### Goal: Production-deployable ledger with performance optimization and observability

### Features

- **Account snapshots**: Incremental balance snapshot with CAS update — eliminates full `SUM()` on balance reads
- **Redis balance cache**: 5-second TTL with DEL invalidation on posting
- **Outbox pattern + Kafka**: `posting.completed` and `balance.changed` events published reliably
- **gRPC API**: High-throughput posting and balance read endpoints alongside REST
- **PostgreSQL partitioning**: `journal_entries` partitioned by month (`effective_at`) via `pg_partman`
- **Read replicas**: Balance reads routed to replica; writes to primary
- **PgBouncer**: Connection pooling in transaction mode
- **Structured logging + tracing**: OpenTelemetry, correlation IDs, JSON log format
- **Prometheus metrics**: Core posting metrics, snapshot CAS retries, cache hit rate
- **Grafana dashboards**: Posting health, financial integrity, balance read performance
- **Alerting**: P0 alerts for invariant violations, P1 for latency/error rate
- **CI/CD pipeline**: GitHub Actions → Docker build → Kubernetes canary deploy
- **Integration environment**: Staging with production-like PostgreSQL

### Architecture

- 5 Kubernetes pods (HPA enabled)
- PostgreSQL primary + 2 read replicas
- Redis Cluster (3 nodes)
- Kafka cluster (3 brokers)
- PgBouncer DaemonSet
- Istio service mesh for mTLS

### Infra

- AWS RDS PostgreSQL Multi-AZ
- AWS ElastiCache Redis Cluster
- AWS MSK (Managed Kafka)
- GitHub Actions CI/CD
- Argo Rollouts for canary deployment

### Risks

- Snapshot CAS contention for hot accounts not yet addressed
- Single-region deployment — regional failure causes outage
- No multi-currency support

### Team

- 3–4 backend engineers

---

## V2 (Months 5–9): Scale & Compliance Features

### Goal: Handle 5,000 posting RPS sustainably with compliance and multi-currency

### Features

- **Hot account mitigation**: Virtual account sharding for platform float and other hot accounts
- **Async snapshot updates**: Option to update snapshot asynchronously (emergency mode + V2 default for very hot accounts)
- **Multi-currency postings**: FX conversion entries, per-currency invariant validation
- **Point-in-time balance API**: Efficient query via snapshot + delta journal
- **Period close enforcement**: Lock backdated entries beyond a closed accounting period
- **Reconciliation engine**: External file ingestion (SFTP/S3), matching algorithm, discrepancy report
- **GL report generation**: Trial balance, account statement — runs against read replica
- **Multi-region standby**: Read replica in secondary region for DR
- **Data archival**: Partitions older than 2 years moved to read-only tablespace; 7-year archival to S3 (Athena queryable)
- **Schema registry**: Avro schemas for all Kafka events; BACKWARD compatibility enforced
- **Financial integrity audit**: Daily automated snapshot vs journal consistency check

### Architecture

- CQRS split: Posting Write Service + Balance Read Service (separate deployments, shared DB)
- 10+ Kubernetes pods (write) + 10+ pods (read service)
- Multi-region PostgreSQL: primary in region-A, standby in region-B
- CDC consideration: Debezium for sub-100ms outbox latency (replacing polling relay)

### Infra

- Multi-AZ + multi-region RDS
- AWS S3 + Athena for archived journal data
- Confluent Schema Registry
- Chaos engineering framework (Chaos Mesh) for quarterly DR exercises

### Risks

- CQRS split increases operational complexity — two deployments to manage
- Multi-currency adds edge cases in invariant checking that require careful test coverage
- Archival pipeline requires careful testing — data must remain queryable post-archival

### Team

- 4–6 backend engineers + 1 SRE

---

## V3 (Year 2+): Enterprise Scale & Multi-Entity

### Goal: 50,000+ posting RPS, multi-entity GL, cross-region active-active

### Features

- **Account sharding**: Horizontal PostgreSQL sharding by account_id range; 16+ shards
- **Distributed saga for cross-shard postings**: Multi-leg postings spanning different shards use saga with compensating entries
- **OLAP integration**: All journal events streamed to ClickHouse or BigQuery for GL reports and analytics
- **Multi-entity ledger**: Holding company + subsidiary ledgers; inter-entity eliminations for consolidated reporting
- **Real-time balance streaming**: `WatchBalance` gRPC streaming endpoint — consumers receive balance updates in real time
- **Regulatory reporting integration**: Direct feeds to RBI, SEBI regulatory submission APIs
- **Active-active multi-region**: Two regions both accepting writes for non-overlapping account ranges; global routing
- **Ledger-as-a-service**: Multi-tenant mode — separate chart of accounts per tenant, tenant-level isolation

### Architecture

- 16 PostgreSQL shards (account_id hash-based routing)
- Dedicated OLAP cluster (ClickHouse) for analytical queries
- Kafka Streams for real-time balance aggregation across shards
- Multi-region active-active with conflict-free account partitioning

### Infra

- Multi-region Kubernetes clusters (EKS in us-east-1, ap-south-1)
- Global load balancer (AWS Global Accelerator or Cloudflare)
- ClickHouse cluster for OLAP
- Advanced Kafka cluster (dedicated cluster per region, MirrorMaker 2 for cross-region replication)

### Risks

- Cross-shard saga-based postings: ACID guarantee weakened to eventual consistency for cross-shard transactions
- Active-active: requires strict account-range partitioning to prevent write conflicts
- Multi-tenant: isolation guarantees must be verified end-to-end — tenant A must never see tenant B's journal entries

### Team

- 8–12 backend engineers + 2 SREs + 1 data engineer

---

## Phase Summary

| Phase | Throughput | Key Capability | Duration | Team Size |
|---|---|---|---|---|
| MVP | < 500 RPS | Core ledger, idempotency, REST API | 6 weeks | 2 engineers |
| V1 | 5,000 RPS | Snapshots, Kafka, gRPC, partitioning, observability | 10 weeks | 4 engineers |
| V2 | 20,000 RPS | Multi-currency, CQRS, reconciliation, archival | 4 months | 6 engineers |
| V3 | 50,000+ RPS | Sharding, OLAP, multi-entity, active-active | 6+ months | 12 engineers |

---

## Adoption: Migrating an Existing Platform onto the Ledger

The ledger is a core primitive — most adopters already have money movement recorded somewhere (a `transactions` table, a payment provider's reports, spreadsheets). The roadmap above builds the service; this section defines how an existing platform cuts over to it without losing or corrupting financial history. Skipping this is the most common real-world failure mode for a new ledger.

### Migration Phases

1. **Backfill (historical load)**
   - Export historical money movements from the legacy store in chronological order
   - Transform each into a balanced multi-leg posting; unbalanced legacy records get an explicit `MIGRATION_ADJUSTMENT` leg against a dedicated migration suspense account — never silently "fix" history
   - Load via the standard posting API with deterministic idempotency keys (`legacy:<source>:<legacy_txn_id>`) so the backfill is restartable and re-runnable
   - Opening balances: one opening posting per account against `OPENING_BALANCE` equity account, dated at the migration cut date — do not fabricate per-transaction history older than the export horizon
2. **Parallel run (shadow mode, 2–4 weeks minimum)**
   - Legacy system remains the source of truth; every new money movement is dual-written to the ledger (async, via the same events the outbox already publishes)
   - Daily reconciliation job compares per-account balances: legacy vs ledger. Mismatches block cutover — each one is a bug in transformation logic or a legacy data quality issue, and both must be explained, not waived
3. **Cutover**
   - Freeze window: stop writes, drain dual-write queue, run final reconciliation, flip reads/writes to the ledger
   - Legacy store becomes read-only archive; the migration suspense account must be zero or formally signed off by finance
4. **Rollback plan**
   - Until cutover + one reconciliation cycle, the dual-write path can be reversed (ledger → shadow, legacy → primary). After that, rollback is a new migration — state this explicitly to stakeholders before cutover

### Design Consequences

- Idempotency keys must accept a caller-supplied namespace (already in the API design) — the migration depends on it
- Postings accept an explicit `posted_at` in the past **only** via a privileged migration scope (normal API rejects backdating; soft-close periods from V2 must exempt the migration window)
- Reconciliation API (V1 feature) is a migration prerequisite, not an optional nicety — sequence it before any adopter onboards

### Migration Risks

- Legacy data that never balanced (most of it won't) — suspense account discipline and finance sign-off, not silent correction
- Backfill volume: 100M legacy rows at 5,000 postings/sec ≈ 6 hours; partition-friendly because backfill is append-only and per-account ordered
- Dual-write divergence during parallel run — treat any unreconciled day as a cutover blocker

---

## What Should NOT Be Built Prematurely

| Feature | Why to Defer |
|---|---|
| Account sharding | Single PostgreSQL handles 5,000 TPS for INSERTs — sharding is complex; defer until forced |
| CQRS split | Adds deployment complexity; read replicas handle most read scale until 20K RPS |
| Multi-currency | Most fintech startups operate in one currency for years; adds invariant complexity |
| CDC (Debezium) | Outbox polling adds < 1s latency — acceptable; CDC infra is complex to operate |
| Active-active multi-region | Strong consistency and active-active are in tension; single active region until forced by regulatory requirement |

---

## Interview Discussion Points

- **Why not build a sharded system from day 1?** Premature sharding sacrifices ACID guarantees (cross-shard transactions require distributed saga), adds operational complexity, and is usually unnecessary — most fintech companies never reach the throughput where single-node PostgreSQL is the bottleneck
- **What is the most critical V1 feature?** Account snapshots. Without them, every balance read is a full table scan of `journal_entries`. At 50,000 balance reads/second (payment pre-authorization), this would immediately saturate PostgreSQL
- **What would you cut from V1 if timeline was short?** gRPC (keep REST), CDC (keep outbox polling), schema registry (plain JSON Kafka events temporarily). Never cut: snapshots, idempotency, partitioning, or alerting on invariant violations
- **At what point do you extract the ledger from a monolith?** When the ledger's scaling requirements diverge from the monolith — typically when the posting throughput or the operational complexity of the ledger (partitions, replicas, snapshots) makes it a deployment risk for the rest of the monolith. This usually happens at Year 1–2 for a growing fintech
