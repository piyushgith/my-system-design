# 05 — Database Design: Double-Entry Ledger Service

---

## Objective

Design the database schema, indexing strategy, partitioning, archival, and consistency model for the double-entry ledger. The journal is the financial source of truth — schema decisions here are permanent.

---

## Database Technology Choice: PostgreSQL

**Why PostgreSQL?**

| Requirement | PostgreSQL Feature |
|---|---|
| Multi-row atomic INSERT (multi-leg posting) | Full ACID transactions |
| Append-only journal enforcement | CHECK constraints, no UPDATE/DELETE privileges for app user |
| Constraint enforcement (debit=credit) | Could be enforced via trigger or application |
| Point-in-time queries | Native `timestamptz` with full precision |
| Long retention (7+ years) | Table partitioning by month/year for archival |
| Optimistic locking for snapshots | `version` column + CAS update |
| Advisory locks for idempotency | `pg_try_advisory_xact_lock()` |
| JSON metadata | `jsonb` with GIN indexing |

**NoSQL alternatives considered and rejected:**

| Alternative | Why Rejected |
|---|---|
| Cassandra | No multi-row transactions — cannot guarantee atomic multi-leg posting |
| MongoDB | Multi-document transactions added in 4.0 but weaker isolation guarantees at scale |
| DynamoDB | Transactions limited to 25 items; no flexible aggregation for balance computation |

---

## Schema Design

### Table: `accounts`

```sql
CREATE TABLE accounts (
    account_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_code    VARCHAR(64) NOT NULL UNIQUE,
    account_name    VARCHAR(255) NOT NULL,
    account_type    VARCHAR(20) NOT NULL CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
    normal_balance  VARCHAR(6)  NOT NULL CHECK (normal_balance IN ('DEBIT','CREDIT')),
    currency        CHAR(3)     NOT NULL,
    owner_id        UUID,
    owner_type      VARCHAR(20) CHECK (owner_type IN ('USER','ORGANIZATION','INTERNAL')),
    status          VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    metadata        JSONB
);

CREATE INDEX idx_accounts_owner ON accounts(owner_id, owner_type) WHERE status = 'ACTIVE';
CREATE INDEX idx_accounts_code ON accounts(account_code);
```

---

### Table: `postings`

```sql
CREATE TABLE postings (
    posting_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key     VARCHAR(255) NOT NULL UNIQUE,
    reference_type      VARCHAR(50) NOT NULL,
    reference_id        UUID,
    status              VARCHAR(20) NOT NULL DEFAULT 'POSTED' CHECK (status IN ('PENDING','POSTED','REVERSED')),
    reversal_of         UUID REFERENCES postings(posting_id),
    effective_at        TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    description         TEXT,
    metadata            JSONB
);

CREATE INDEX idx_postings_idempotency ON postings(idempotency_key);
CREATE INDEX idx_postings_reference ON postings(reference_type, reference_id);
CREATE INDEX idx_postings_effective ON postings(effective_at DESC);
```

**Design note:** `idempotency_key` UNIQUE constraint is the last line of defense against duplicate postings (behind Redis cache and application-layer check).

---

### Table: `journal_entries` (Partitioned)

This is the core ledger table. Partitioned by month based on `effective_at`.

```sql
CREATE TABLE journal_entries (
    entry_id        UUID            NOT NULL DEFAULT gen_random_uuid(),
    posting_id      UUID            NOT NULL REFERENCES postings(posting_id),
    account_id      UUID            NOT NULL REFERENCES accounts(account_id),
    direction       VARCHAR(6)      NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount          BIGINT          NOT NULL CHECK (amount > 0),
    currency        CHAR(3)         NOT NULL,
    effective_at    TIMESTAMPTZ     NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    description     TEXT,
    sequence_num    BIGSERIAL,
    PRIMARY KEY (entry_id, effective_at)
) PARTITION BY RANGE (effective_at);

-- Monthly partitions
CREATE TABLE journal_entries_2024_01 PARTITION OF journal_entries
    FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');
CREATE TABLE journal_entries_2024_02 PARTITION OF journal_entries
    FOR VALUES FROM ('2024-02-01') TO ('2024-03-01');
-- ... auto-generated monthly via pg_partman
```

**Partition Indexes (created on each partition):**
```sql
-- Hot path: account balance queries
CREATE INDEX idx_je_account_effective ON journal_entries(account_id, effective_at DESC, entry_id);

-- Posting lookup
CREATE INDEX idx_je_posting ON journal_entries(posting_id);

-- Balance computation since watermark
CREATE INDEX idx_je_sequence ON journal_entries(account_id, sequence_num DESC);
```

**Why partition by `effective_at` (not `created_at`)?**

Financial queries use business dates (effective_at) — "what was the balance on Jan 31?" The partition key must match the most common query predicate for partition pruning.

---

### Table: `account_snapshots`

```sql
CREATE TABLE account_snapshots (
    account_id          UUID PRIMARY KEY REFERENCES accounts(account_id),
    balance             BIGINT      NOT NULL DEFAULT 0,
    currency            CHAR(3)     NOT NULL,
    last_entry_id       UUID,
    last_sequence_num   BIGINT      NOT NULL DEFAULT 0,
    last_posting_id     UUID,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT      NOT NULL DEFAULT 0
);
```

**Snapshot update strategy (CAS — Compare-And-Swap):**
```sql
UPDATE account_snapshots
SET balance = balance + :delta,
    last_entry_id = :entryId,
    last_sequence_num = :sequenceNum,
    last_posting_id = :postingId,
    updated_at = now(),
    version = version + 1
WHERE account_id = :accountId
  AND version = :expectedVersion;
```

If `0 rows updated`: concurrent posting detected — retry after reading latest version. Optimistic locking prevents balance corruption without row-level locking for every balance update.

---

### Table: `outbox_events`

```sql
CREATE TABLE outbox_events (
    event_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    posting_id      UUID REFERENCES postings(posting_id),
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    published       BOOLEAN     NOT NULL DEFAULT false,
    retry_count     INT         NOT NULL DEFAULT 0
);

CREATE INDEX idx_outbox_unpublished ON outbox_events(created_at) WHERE published = false;
```

---

### Table: `idempotency_cache` (Database backup for Redis)

```sql
CREATE TABLE idempotency_cache (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    posting_id      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL DEFAULT now() + INTERVAL '7 days'
);

CREATE INDEX idx_idempotency_expiry ON idempotency_cache(expires_at);
```

Redis is primary. This table is the fallback if Redis is unavailable — prevents data loss on cache restart.

---

## Partitioning Strategy

| Strategy | Choice | Reason |
|---|---|---|
| Table | Range by `effective_at` (monthly) | Financial queries are date-range — partition pruning eliminates old partitions |
| Partition size | ~1 month per partition | Manageable indexes; archival at partition level |
| Future sharding | Shard by `account_id` hash prefix when single PG saturates | Each shard is a full PG instance with its own partitions |

**Partition Management:**
- `pg_partman` extension auto-creates future partitions
- Old partitions (beyond 2 years) moved to read-only tablespace on slower storage
- Partitions older than 7 years archived to S3 as Parquet files via COPY TO — queryable via DuckDB or Athena

---

## Indexing Strategy

| Index | Table | Columns | Purpose |
|---|---|---|---|
| Primary | postings | posting_id | Fast lookup by ID |
| Unique | postings | idempotency_key | Duplicate prevention |
| Balance query | journal_entries | (account_id, effective_at DESC, entry_id) | Account statement and balance aggregation |
| Outbox poll | outbox_events | created_at WHERE published=false | Relay job |
| Snapshot lock | account_snapshots | account_id + version | CAS updates |
| Account owner | accounts | (owner_id, owner_type) | Find accounts for a user |

**Index maintenance:**
- Append-only table = no index bloat from UPDATEs/DELETEs
- `VACUUM` still needed for dead tuples from rolled-back transactions
- `pg_stat_user_indexes` monitored weekly for unused indexes

---

## Consistency Model

| Scenario | Consistency Required | Mechanism |
|---|---|---|
| Multi-leg posting commit | Serializable | PostgreSQL transaction — all-or-nothing |
| Balance snapshot update | Optimistic concurrency | CAS with version column |
| Idempotency key uniqueness | Serializable | UNIQUE constraint + Redis pre-check |
| Balance cache reads | Eventual (within 5s) | Redis TTL expiry |
| Outbox relay to Kafka | At-least-once | Relay polls outbox; Kafka consumer deduplicates |
| Read replicas | Read-committed (replica lag) | Balance queries that must be current use primary |

**PostgreSQL transaction isolation:**
- Posting transactions: `SERIALIZABLE` (or `REPEATABLE READ` with explicit uniqueness check)
- Balance reads: `READ COMMITTED` from replica — snapshot provides accurate-enough value

---

## Data Archival Strategy

| Age | Action | Storage |
|---|---|---|
| 0–2 years | Hot partition, SSD | PostgreSQL primary + replica |
| 2–7 years | Read-only partition | PostgreSQL on HDD tablespace |
| 7+ years | Archived | S3 Parquet, queryable via Athena |

**Archival process:**
1. Run `COPY journal_entries_2017_01 TO 's3://ledger-archive/2017/01.parquet' WITH (FORMAT parquet)`
2. Detach partition from main table
3. Compress and retain on S3 for regulatory audit access
4. Drop partition from PostgreSQL

**Point-in-time queries on archived data:** Route to Athena for historical queries beyond 7 years.

---

## Soft Delete Strategy

Journal entries are **never deleted or soft-deleted.** Deletion is represented by a reversal posting. This is a fundamental accounting principle — the journal is an immutable fact log.

For accounts:
- `status = CLOSED` prevents new entries but the account row remains forever
- `closed_at` timestamp records when the account was closed

---

## Audit Strategy

Every row in `journal_entries` is immutable after INSERT. No UPDATE or DELETE is permitted on this table for the application user.

**PostgreSQL row-level security:**
```sql
-- App user role: insert only, no update/delete
REVOKE UPDATE, DELETE ON journal_entries FROM ledger_app_user;
REVOKE UPDATE, DELETE ON postings FROM ledger_app_user;
```

Audit requirements are satisfied by the journal itself — every financial event is recorded as an entry with `created_at` and `posting_id` linking back to the business event.

---

## Interview Discussion Points

- **Why BIGINT for amounts instead of NUMERIC?** BIGINT (64-bit integer) is faster than NUMERIC for arithmetic — no arbitrary precision overhead. Storing amounts in the smallest currency unit (paise, cents) eliminates the decimal problem. NUMERIC only needed for FX rates (4 decimal places) which are rarely stored in the ledger
- **Why not use a running balance column instead of aggregating?** A running balance column requires updating on every INSERT — creates a sequential dependency (entry N must wait for entry N-1). For concurrent postings on the same account, this becomes a serialization bottleneck. Snapshot table is a better isolation of the aggregation concern
- **How do you handle the partition key for future-dated postings (effective_at in the future)?** Future partitions must exist or the INSERT fails. `pg_partman` configured to create 12 months of future partitions proactively. Backfill via separate process if a new partition is needed on-the-fly
- **What is the hot account problem?** A high-frequency account (e.g., a platform float account that receives all incoming payments) can serialize many concurrent postings. Mitigation: sharding the float account into N virtual accounts with periodic consolidation posting; or batch posting with a buffer
- **Why not use a time-series database like TimescaleDB?** TimescaleDB is excellent for metrics. But the ledger needs JOIN capability across postings, accounts, and entries within a transaction — PostgreSQL's relational model is essential. TimescaleDB IS PostgreSQL; use it for the `journal_entries` hypertable if needed as a drop-in
