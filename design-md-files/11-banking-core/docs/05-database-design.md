# 05 — Database Design: Banking Core System

## Objective

Design the relational database schema for the Banking Core System. Cover table structures, partitioning strategy, indexing, sharding considerations, audit trails, soft deletes, data archival, and the critical decision to use immutable ledger tables. Every design decision is justified through a banking lens.

---

## 1. Why PostgreSQL

| Requirement | PostgreSQL Capability |
|---|---|
| ACID transactions | Full ACID — critical for double-entry ledger |
| Row-level locking | Fine-grained locking for concurrent balance operations |
| Advisory locks | Application-level locks for idempotency without table locks |
| Partitioning | Native table partitioning for journal entries (by date) |
| JSON support | JSONB for audit payload storage without schema explosion |
| WAL-based CDC | Write-ahead log → Debezium → Kafka for event sourcing |
| Read replicas | Streaming replication for read-heavy reporting workloads |
| Extensions | pg_partman for partition management, TimescaleDB for time-series metrics |
| Mature ecosystem | Spring Data JPA, Flyway migrations, pg_dump for backups |

**When to consider alternatives**:
- If journal table exceeds 5TB and query patterns shift heavily toward analytics → consider migrating historical data to a columnar store (Redshift, BigQuery)
- If time-series metrics (balance history) need sub-second queries at high cardinality → TimescaleDB hypertables

---

## 2. Schema Overview

```
Schemas:
├── cif           — Customer Identity & KYC
├── accounts      — Account Management & Products
├── ledger        — Transactions & Journal (immutable)
├── payments      — Payment instructions & lifecycle
├── approvals     — Maker-checker workflow
├── compliance    — AML alerts, KYC cases, regulatory filings
├── batch         — Job control tables for Spring Batch
└── audit         — Cross-module audit event log
```

---

## 3. Core Table Designs

### 3.1 Customer Tables (cif schema)

```sql
-- CIF Master
cif.customers (
    cif_id          VARCHAR(20) PRIMARY KEY,   -- Bank-assigned: CIF-10000001
    first_name      VARCHAR(100) NOT NULL,
    last_name       VARCHAR(100) NOT NULL,
    date_of_birth   DATE NOT NULL,
    gender          VARCHAR(10),
    pan_hash        VARCHAR(64) NOT NULL UNIQUE, -- SHA-256 of PAN, never plaintext
    aadhaar_token   VARCHAR(72) UNIQUE,          -- Tokenized, not raw Aadhaar
    customer_type   VARCHAR(20) NOT NULL,        -- INDIVIDUAL, COMPANY
    status          VARCHAR(20) NOT NULL,        -- ACTIVE, FROZEN, CLOSED
    risk_rating     VARCHAR(10) NOT NULL,        -- LOW, MEDIUM, HIGH
    pep_flag        BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version         BIGINT NOT NULL DEFAULT 0    -- Optimistic locking
)

-- KYC Documents
cif.kyc_records (
    kyc_id          UUID PRIMARY KEY,
    cif_id          VARCHAR(20) NOT NULL REFERENCES cif.customers(cif_id),
    kyc_type        VARCHAR(30) NOT NULL,        -- FULL, REFRESH, VIDEO
    status          VARCHAR(20) NOT NULL,        -- PENDING, VERIFIED, REJECTED, EXPIRED
    verified_by     VARCHAR(50),
    verified_at     TIMESTAMPTZ,
    expiry_date     DATE,
    document_refs   JSONB,                       -- S3 keys for uploaded documents
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
)
-- Index: cif_id, status for "customers needing KYC renewal" queries
```

---

### 3.2 Account Tables (accounts schema)

```sql
-- Account Master
accounts.accounts (
    account_id      VARCHAR(25) PRIMARY KEY,     -- SAV-10000001, FD-20000001
    cif_id          VARCHAR(20) NOT NULL,
    account_type    VARCHAR(20) NOT NULL,        -- SAVINGS, CURRENT, FD, RD, LOAN
    product_code    VARCHAR(30) NOT NULL,
    status          VARCHAR(20) NOT NULL,        -- PENDING, ACTIVE, FROZEN, DORMANT, CLOSED
    currency        CHAR(3) NOT NULL DEFAULT 'INR',
    current_balance NUMERIC(20,4) NOT NULL DEFAULT 0,
    available_balance NUMERIC(20,4) NOT NULL DEFAULT 0,
    overdraft_limit NUMERIC(20,4) NOT NULL DEFAULT 0,
    open_date       DATE NOT NULL,
    closure_date    DATE,
    last_txn_date   DATE,
    dormancy_date   DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version         BIGINT NOT NULL DEFAULT 0
)

-- Lien / Hold Table
accounts.liens (
    lien_id         UUID PRIMARY KEY,
    account_id      VARCHAR(25) NOT NULL,
    amount          NUMERIC(20,4) NOT NULL,
    reason          VARCHAR(200) NOT NULL,
    status          VARCHAR(20) NOT NULL,        -- ACTIVE, RELEASED, EXPIRED
    lien_type       VARCHAR(30),                 -- PAYMENT_HOLD, LEGAL_HOLD, CHEQUE_HOLD
    reference_id    VARCHAR(50),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ,
    released_at     TIMESTAMPTZ,
    released_by     VARCHAR(50)
)
-- Partial index: WHERE status = 'ACTIVE' — used for available balance calculation
```

---

### 3.3 Ledger Tables (ledger schema) — IMMUTABLE

The most critical design decision: **journal_entries can never be updated or deleted**. This is enforced at the DB level via:
- No `UPDATE` privilege granted on `journal_entries` to the application role
- No `DELETE` privilege granted on `journal_entries` to the application role
- Row-level security: application user has INSERT + SELECT only
- Audit triggers: any attempt to modify is logged to the audit schema

```sql
-- Transaction Master
ledger.transactions (
    txn_id          VARCHAR(30) PRIMARY KEY,    -- TXN-20240115-XXXXXX
    txn_type        VARCHAR(30) NOT NULL,       -- TRANSFER, PAYMENT_DEBIT, INTEREST_CREDIT, etc.
    status          VARCHAR(20) NOT NULL,       -- PENDING, POSTED, REVERSED, FAILED
    amount          NUMERIC(20,4) NOT NULL,
    currency        CHAR(3) NOT NULL,
    posting_date    DATE NOT NULL,
    value_date      DATE NOT NULL,
    narration       VARCHAR(500),
    reference_number VARCHAR(50),
    idempotency_key VARCHAR(100) UNIQUE,        -- Dedup key
    initiated_by    VARCHAR(50) NOT NULL,
    initiated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    posted_at       TIMESTAMPTZ,
    reversal_of     VARCHAR(30)                 -- If this is a reversal, parent txn_id
    -- NO updated_at, NO version — immutable once POSTED
)

-- Journal Entries (the actual double-entry ledger)
ledger.journal_entries (
    entry_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    txn_id          VARCHAR(30) NOT NULL,
    account_id      VARCHAR(25) NOT NULL,
    gl_code         VARCHAR(20) NOT NULL,       -- Chart of accounts code
    entry_type      CHAR(1) NOT NULL CHECK (entry_type IN ('D', 'C')), -- Debit/Credit
    amount          NUMERIC(20,4) NOT NULL CHECK (amount > 0),
    currency        CHAR(3) NOT NULL,
    value_date      DATE NOT NULL,
    posting_date    DATE NOT NULL,
    posted_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    narration       VARCHAR(500)
    -- NO PRIMARY KEY mutation possible — INSERT ONLY
)
PARTITION BY RANGE (posting_date);

-- Create monthly partitions
CREATE TABLE ledger.journal_entries_2024_01
    PARTITION OF ledger.journal_entries
    FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');
```

**Partitioning Strategy for Journal Entries**:
- Partition by `posting_date` (monthly partitions)
- Query patterns are almost always time-bounded (last month, last quarter)
- Old partitions can be archived to cheaper storage (S3/Glacier) after 2 years
- Partition pruning makes recent queries extremely fast
- `pg_partman` automates monthly partition creation

---

### 3.4 Payment Tables (payments schema)

```sql
-- Payment Instructions
payments.payments (
    payment_id      VARCHAR(30) PRIMARY KEY,
    payment_type    VARCHAR(20) NOT NULL,       -- NEFT, RTGS, IMPS, SWIFT
    status          VARCHAR(30) NOT NULL,       -- INITIATED, PENDING_APPROVAL, ...
    source_account  VARCHAR(25) NOT NULL,
    amount          NUMERIC(20,4) NOT NULL,
    currency        CHAR(3) NOT NULL,
    purpose_code    VARCHAR(20),
    narration       VARCHAR(500),
    idempotency_key VARCHAR(100) UNIQUE,
    txn_id          VARCHAR(30),                -- Linked ledger transaction
    utr_number      VARCHAR(50),               -- Payment rail UTR
    retry_count     SMALLINT DEFAULT 0,
    initiated_by    VARCHAR(50) NOT NULL,
    initiated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    submitted_at    TIMESTAMPTZ,
    settled_at      TIMESTAMPTZ,
    failure_reason  VARCHAR(500)
)

-- Beneficiary Details (separate for normalization + security)
payments.beneficiaries (
    beneficiary_id  UUID PRIMARY KEY,
    payment_id      VARCHAR(30) NOT NULL REFERENCES payments.payments(payment_id),
    account_number_encrypted VARCHAR(500) NOT NULL, -- AES-256 encrypted
    ifsc_code       CHAR(11) NOT NULL,
    beneficiary_name VARCHAR(200),
    bank_name       VARCHAR(100)
)

-- Payment Outbox (Transactional Outbox Pattern)
payments.payment_outbox (
    outbox_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id    VARCHAR(30) NOT NULL,       -- payment_id
    event_type      VARCHAR(50) NOT NULL,       -- PaymentInitiated, PaymentSettled
    payload         JSONB NOT NULL,
    status          VARCHAR(20) DEFAULT 'PENDING', -- PENDING, PUBLISHED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ
)
```

---

### 3.5 Approval Tables (approvals schema)

```sql
approvals.approval_requests (
    request_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operation_type  VARCHAR(50) NOT NULL,       -- ACCOUNT_OPEN, HIGH_VALUE_PAYMENT, etc.
    status          VARCHAR(20) NOT NULL,       -- PENDING, APPROVED, REJECTED, ESCALATED
    entity_type     VARCHAR(30) NOT NULL,
    entity_id       VARCHAR(50) NOT NULL,
    proposed_data   JSONB NOT NULL,             -- The change being proposed
    current_data    JSONB,                      -- Snapshot of current state
    maker_id        VARCHAR(50) NOT NULL,
    maker_role      VARCHAR(30) NOT NULL,
    checker_id      VARCHAR(50),
    checker_role    VARCHAR(30),
    decision_comments VARCHAR(1000),
    rejection_reason  VARCHAR(1000),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ NOT NULL,
    decided_at      TIMESTAMPTZ
)
-- This table IS immutable after decision — no updates to decided records
```

---

## 4. Audit Strategy

### Immutable Audit Log Table
```sql
audit.audit_events (
    audit_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(50) NOT NULL,
    entity_type     VARCHAR(30),
    entity_id       VARCHAR(50),
    actor_id        VARCHAR(50) NOT NULL,
    actor_role      VARCHAR(30),
    actor_ip        INET,
    session_id      VARCHAR(100),
    correlation_id  VARCHAR(100),
    old_state       JSONB,
    new_state       JSONB,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
)
PARTITION BY RANGE (occurred_at);
```

- **INSERT-only** — application role has no UPDATE/DELETE on this table
- Partitioned monthly — old partitions archived to S3 after 90 days, accessible via pg_restore or Athena
- 10-year retention requirement met by archival to S3 (cold storage)
- Separate read replica dedicated to audit queries — doesn't compete with operational traffic

---

## 5. Indexing Strategy

| Table | Index | Type | Rationale |
|---|---|---|---|
| `accounts.accounts` | `(cif_id)` | B-tree | Fetch all accounts for a customer |
| `accounts.accounts` | `(status, account_type)` | B-tree | Filter active savings accounts |
| `accounts.liens` | `(account_id) WHERE status='ACTIVE'` | Partial B-tree | Fast available balance calculation |
| `ledger.journal_entries` | `(account_id, value_date DESC)` | B-tree | Transaction history per account |
| `ledger.journal_entries` | `(txn_id)` | B-tree | Fetch all entries for a transaction |
| `ledger.transactions` | `(idempotency_key)` | Unique B-tree | Idempotency dedup |
| `ledger.transactions` | `(posting_date, status)` | B-tree | EOD batch queries |
| `payments.payments` | `(source_account, status)` | B-tree | Pending payments per account |
| `payments.payments` | `(utr_number)` | Unique B-tree | Rail reference lookup |
| `payments.payment_outbox` | `(status, created_at) WHERE status='PENDING'` | Partial B-tree | Outbox processor polling |
| `cif.customers` | `(pan_hash)` | Unique B-tree | PAN dedup check |
| `audit.audit_events` | `(entity_id, occurred_at)` | B-tree | Audit trail per entity |

**Index Hygiene**:
- Unused indexes waste write overhead — monitor with `pg_stat_user_indexes`
- BRIN indexes for time-series tables (journal entries partitioned by date) — space-efficient for sequential inserts
- No indexes on encrypted columns (account_number_encrypted) — encrypt → hash for lookup if needed

---

## 6. Optimistic vs Pessimistic Locking

### Accounts: Pessimistic Locking for Balance Updates
For concurrent balance modifications, use `SELECT ... FOR UPDATE` on the account row. This serializes concurrent transactions against the same account.

**Rationale**: The risk of two transactions simultaneously debiting the same account (race condition) is catastrophic — potential overdraft. Pessimistic lock is correct here. The lock is short-lived (transaction duration = milliseconds).

**Alternative — Advisory Locks**: PostgreSQL advisory locks (`pg_try_advisory_xact_lock(account_id_hash)`) can serialize updates without holding a row-level lock. Useful when the transaction spans multiple steps.

### ApprovalRequests: Optimistic Locking
Approval decisions are less contended. Optimistic locking via `version` column:
- Read the record (version = 5)
- Update with `WHERE version = 5`
- If zero rows affected → another checker already decided → return 409 Conflict

---

## 7. Data Archival Strategy

| Table | Hot Retention | Warm Retention | Cold Archive |
|---|---|---|---|
| `journal_entries` | 2 years in PostgreSQL | 3-5 years on read replica | 7 years on S3/Glacier |
| `audit_events` | 90 days in PostgreSQL | N/A | 10 years on S3 |
| `transactions` | 2 years in PostgreSQL | N/A | 7 years on S3 |
| `payments` | 1 year in PostgreSQL | N/A | 7 years on S3 |
| `customers` | Active lifetime | N/A | Closed customers archived after 5 years |

**Archival Mechanism**:
- Partition detach → pg_dump partition → compress (zstd) → upload to S3
- AWS Athena can query archived Parquet files without restoring to PostgreSQL
- Regulatory queries on archived data use Athena — response time in seconds, not milliseconds

---

## 8. Multi-Tenancy Considerations (Future)

If the bank offers white-label CBS to smaller banks:
- **Schema-per-tenant**: Each tenant gets a separate PostgreSQL schema. Row-level security enforces isolation. Works for < 50 tenants.
- **Database-per-tenant**: Each tenant gets a dedicated PostgreSQL instance. Complete isolation but high operational overhead.
- **Row-level tenant_id**: Single table with `bank_id` column and row-level security (RLS). Scales to many tenants but complex query planning.

For a single-bank deployment: no multi-tenancy needed — avoid this complexity.

---

## 9. Sharding Considerations

At 5M customers and 8M accounts, PostgreSQL handles this comfortably on a single server with vertical scaling (64-core, 512GB RAM). Sharding is premature.

**When to consider sharding**:
- Journal entries table exceeds 10TB and query performance degrades despite partitioning
- Write TPS consistently exceeds PostgreSQL's single-master limit (~20,000 write TPS)
- At this scale: shard by `account_id % N` — ensures all journal entries for an account are co-located
- Migration path: Citus extension for PostgreSQL distributed tables, or migrate to CockroachDB

**The Taking reality**: JPMorgan's ledger shards by account range with ~200 shards. They also use custom hardware. A mid-size bank should not replicate this — it adds enormous operational complexity for a problem they won't face for years.

---

## 10. Read Replica Strategy

```
Primary DB (write): Transaction posting, account updates, payments
Read Replica 1:     Customer portal balance reads (slight staleness acceptable)
Read Replica 2:     Reporting, statement generation, AML queries
Read Replica 3:     Audit queries (compliance team, regulators)
```

Replica lag target: < 100ms for customer balance reads, < 5 seconds for reporting.

Reads from replica are labeled in the API response:
```json
{ "balanceAsOf": "2024-01-15T14:31:55Z" }
```

This tells the customer the balance may be up to a few seconds old — sufficient for most reads.

---

## 11. Interview-Level Discussion Points

**Q: Why NUMERIC(20,4) and not FLOAT or DECIMAL?**
A: `FLOAT` uses IEEE 754 floating-point which has binary representation errors. `0.1 + 0.2 = 0.30000000004` in floating-point. For financial calculations, this is unacceptable. `NUMERIC(20,4)` is PostgreSQL's exact decimal type — no rounding error. Scale 4 supports currencies with up to 4 decimal places (Japanese Yen has 0, KWD has 3, some crypto has 8 — need to decide based on product scope).

**Q: How do you guarantee the double-entry balance at the database level?**
A: The application enforces it in the Transaction aggregate. Additionally, a deferred constraint trigger can verify that for every `txn_id`, sum(debits) = sum(credits) at transaction commit time. This is defense-in-depth. If there's a bug in the application code, the database rejects the commit.

**Q: What is the transactional outbox pattern and why use it?**
A: When a payment is created, we need to both: (1) write to the payments table and (2) publish a Kafka event. If we write to the DB and then crash before publishing to Kafka, the event is lost. The outbox pattern writes the event into a `payment_outbox` table within the same DB transaction as the payment. A separate process reads the outbox table and publishes to Kafka. DB atomicity guarantees both writes happen — the outbox processor guarantees eventual Kafka publication.

**Q: How do you handle PAN storage given data sensitivity?**
A: PAN is never stored in plaintext. We store `SHA-256(PAN)` for deduplication lookups (unique constraint on the hash). For display purposes, we store masked PAN (XXXXXX1234). For regulatory reporting where full PAN is required, we use an HSM-backed encrypted store where the application can decrypt with audit trail.
