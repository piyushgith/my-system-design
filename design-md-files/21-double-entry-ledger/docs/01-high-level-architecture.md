# 01 — High-Level Architecture: Double-Entry Ledger Service

---

## Objective

Define the architectural style, service boundaries, and component interaction model for the double-entry ledger service. Justify the chosen architecture and explain migration paths.

---

## Architecture Decision: Modular Monolith with DDD (Event-Sourced Core)

### Chosen Architecture: Modular Monolith + Event Sourcing on the Journal

The ledger is not a microservice. It is a **strongly consistent, domain-rich, financial core** that must guarantee atomic multi-leg postings. Distributing this across multiple services without a distributed transaction protocol (XA or Saga) introduces split-brain risk — a critical financial error.

**Why Modular Monolith?**

- Strong ACID guarantees across all legs of a posting — critical for debit=credit invariant
- Single database transaction scope for multi-leg atomicity
- Simpler operational model for a team that owns the financial source of truth
- The ledger is a bounded context unto itself — it does not need to reach into other domains
- Avoids 2PC (two-phase commit) complexity that distributed microservices would require

**Why Event Sourcing on the Journal Layer?**

The append-only journal IS event sourcing. Journal entries are the event log. Balances are projections derived from the log. This gives us:
- Complete audit trail by design
- Point-in-time replay for any account balance
- Ability to rebuild derived models (balance snapshots, GL views) from the journal
- Natural idempotency (entries are facts, not state mutations)

**When NOT to Use This Architecture**

- If the ledger must span multiple databases in different jurisdictions (use distributed saga with compensating entries instead)
- If posting throughput exceeds 50,000 TPS on a single PostgreSQL instance (then shard and accept eventual GL aggregation)
- If the team is large (10+ engineers) and needs independent deployability — then extract posting service and read service as separate deployments sharing one database

---

## System Context

```mermaid
C4Context
    title Double-Entry Ledger — System Context

    Person(upstream, "Upstream Services", "Payment, Wallet, Loan, Card systems")
    Person(analyst, "Finance/Ops Team", "Reconciliation, GL reports")

    System(ledger, "Double-Entry Ledger Service", "Posts journal entries, computes balances, enforces debit=credit invariant")

    System_Ext(kafka, "Kafka", "Balance change events to downstream consumers")
    System_Ext(postgres, "PostgreSQL", "Append-only journal, account snapshots")
    System_Ext(redis, "Redis", "Balance cache, idempotency hot cache")

    Rel(upstream, ledger, "POST /postings, GET /accounts/{id}/balance", "gRPC or REST")
    Rel(analyst, ledger, "GET /reports, GET /reconciliation", "REST")
    Rel(ledger, postgres, "Reads/writes journal and snapshots")
    Rel(ledger, redis, "Balance cache reads/writes")
    Rel(ledger, kafka, "Publishes balance.changed, posting.completed events")
```

---

## Component Architecture

```mermaid
graph TB
    subgraph Clients
        PAY[Payment Service]
        WAL[Wallet Service]
        LOAN[Loan Service]
        OPS[Ops Dashboard]
    end

    subgraph Ledger Service ["Ledger Service (Modular Monolith)"]
        API[API Layer<br/>REST / gRPC]
        IDEMPOTENCY[Idempotency Guard]
        POSTING[Posting Module]
        BALANCE[Balance Module]
        RECONCILE[Reconciliation Module]
        SNAPSHOT[Snapshot Manager]
        EVTPUB[Event Publisher]
    end

    subgraph Storage
        PG[(PostgreSQL<br/>journal_entries<br/>postings<br/>account_snapshots)]
        REDIS[(Redis<br/>balance cache<br/>idempotency cache)]
    end

    subgraph Downstream
        KAFKA[Kafka<br/>balance.changed<br/>posting.completed]
        FRAUD[Fraud Service]
        ANALYTICS[Analytics Platform]
        RISK[Risk Engine]
    end

    PAY --> API
    WAL --> API
    LOAN --> API
    OPS --> API

    API --> IDEMPOTENCY
    IDEMPOTENCY --> POSTING
    POSTING --> BALANCE
    POSTING --> SNAPSHOT
    POSTING --> EVTPUB

    BALANCE --> REDIS
    BALANCE --> PG

    SNAPSHOT --> PG
    EVTPUB --> KAFKA

    KAFKA --> FRAUD
    KAFKA --> ANALYTICS
    KAFKA --> RISK

    RECONCILE --> PG

    style Ledger Service fill:#f0f4ff,stroke:#4a6cf7
```

---

## Module Responsibilities

| Module | Responsibility |
|---|---|
| **API Layer** | Input validation, auth, rate limiting, protocol translation |
| **Idempotency Guard** | Check Redis then DB for existing posting by idempotency_key; short-circuit duplicates |
| **Posting Module** | Enforce debit=credit, persist all legs atomically, trigger snapshot update |
| **Balance Module** | Serve balance from cache → snapshot → journal fallback chain |
| **Snapshot Manager** | Maintain materialized balance snapshot per account; update incrementally on each posting |
| **Reconciliation Module** | Accept external data, compare to journal, produce discrepancy report |
| **Event Publisher** | Publish posting events to Kafka via Outbox pattern (not direct Kafka write in transaction) |

---

## Request Flow: Posting a Transaction

```mermaid
sequenceDiagram
    participant Client as Payment Service
    participant API as Ledger API
    participant IDP as Idempotency Guard
    participant POST as Posting Module
    participant DB as PostgreSQL
    participant SNAP as Snapshot Manager
    participant EVT as Event Publisher
    participant REDIS as Redis

    Client->>API: POST /postings {idempotency_key, legs: [{account, direction, amount}]}
    API->>IDP: check(idempotency_key)
    IDP->>REDIS: GET idempotency:{key}
    alt Key exists (duplicate)
        REDIS-->>IDP: existing posting_id
        IDP-->>API: return existing result
        API-->>Client: 200 OK (idempotent response)
    else Key not found
        REDIS-->>IDP: nil
        IDP->>DB: SELECT FROM postings WHERE idempotency_key=?
        DB-->>IDP: nil
        IDP-->>POST: proceed
        POST->>POST: validate debit_sum == credit_sum
        POST->>DB: BEGIN TRANSACTION
        POST->>DB: INSERT INTO postings
        POST->>DB: INSERT INTO journal_entries (all legs)
        POST->>DB: UPDATE account_snapshots (incremental)
        POST->>DB: INSERT INTO outbox_events
        POST->>DB: COMMIT
        DB-->>POST: success
        POST->>REDIS: SET idempotency:{key} → posting_id (TTL 24h)
        POST->>SNAP: async notify snapshot refresh
        POST->>EVT: relay outbox events → Kafka
        API-->>Client: 201 Created {posting_id, legs, balances}
    end
```

---

## Balance Read Flow

```mermaid
sequenceDiagram
    participant Client as Payment Authorizer
    participant API as Ledger API
    participant BAL as Balance Module
    participant REDIS as Redis
    participant DB as PostgreSQL

    Client->>API: GET /accounts/{id}/balance
    API->>BAL: getBalance(account_id)
    BAL->>REDIS: GET balance:{account_id}
    alt Cache hit
        REDIS-->>BAL: {balance, snapshot_version}
        BAL-->>API: return cached balance
    else Cache miss
        REDIS-->>BAL: nil
        BAL->>DB: SELECT balance, version FROM account_snapshots WHERE account_id=?
        DB-->>BAL: snapshot row
        BAL->>REDIS: SET balance:{account_id} TTL 5s
        BAL-->>API: return snapshot balance
    end
    API-->>Client: 200 OK {balance, currency, as_of}
```

---

## High-Level Architecture Diagram

```mermaid
graph LR
    subgraph Ingress
        GW[API Gateway<br/>Auth + Rate Limit]
    end

    subgraph LedgerService ["Ledger Service"]
        direction TB
        API[gRPC / REST API]
        CORE[Posting Core]
        SNAP[Snapshot Engine]
        BAL[Balance Reader]
        OUTBOX[Outbox Relay]
        RECON[Reconciliation]
    end

    subgraph DataLayer ["Data Layer"]
        PG_PRIMARY[(PostgreSQL Primary<br/>journal_entries<br/>postings<br/>account_snapshots<br/>outbox)]
        PG_REPLICA[(PostgreSQL Replica<br/>read replicas)]
        REDIS_CLUSTER[(Redis Cluster<br/>balance cache)]
    end

    subgraph Messaging
        KAFKA[Kafka<br/>posting.completed<br/>balance.changed]
    end

    GW --> API
    API --> CORE
    API --> BAL
    API --> RECON
    CORE --> SNAP
    CORE --> PG_PRIMARY
    SNAP --> PG_PRIMARY
    BAL --> REDIS_CLUSTER
    BAL --> PG_REPLICA
    OUTBOX --> PG_PRIMARY
    OUTBOX --> KAFKA

    style LedgerService fill:#e8f5e9,stroke:#388e3c
    style DataLayer fill:#fff3e0,stroke:#f57c00
```

---

## Architectural Tradeoffs

| Decision | Pro | Con |
|---|---|---|
| Modular monolith over microservices | Single DB transaction for multi-leg atomicity | Harder to scale individual modules independently |
| Event sourcing via journal | Complete audit trail, time-travel queries | Storage grows unboundedly; snapshot management adds complexity |
| Synchronous balance snapshot update | Balance always current after posting | Hot-account contention on snapshot row |
| Redis balance cache | Sub-5ms reads for payment authorization | Cache invalidation complexity; brief stale window |
| Outbox for Kafka publishing | Exactly-once delivery guarantee | Extra table, polling relay job required |

---

## Migration Path: Monolith → Microservices (if scale demands)

1. **Phase 1 (current):** Single deployable JAR, single PostgreSQL, single Redis
2. **Phase 2:** Extract read service (Balance Module) to a separate deployment, reading from PostgreSQL replica — still same DB
3. **Phase 3:** Separate posting writes and balance reads at the network boundary — CQRS split
4. **Phase 4:** Shard journal by account_id range across multiple PostgreSQL instances — GL aggregation becomes eventually consistent across shards
5. **Phase 5:** Extract reconciliation and GL reporting to separate service with its own read-optimized data store (ClickHouse or Redshift for large aggregations)

---

## Interview Discussion Points

- **Why not use a distributed ledger (blockchain)?** Distributed consensus is 100–1000x slower than ACID PostgreSQL for the same durability. Enterprise finance does not need trustless consensus — it needs correctness and auditability
- **What would break first?** The `account_snapshots` row for a hot account becomes a lock contention point at high posting frequency. Mitigation: snapshot update via `FOR UPDATE SKIP LOCKED` with a queue, or batch snapshot updates
- **How do you handle cross-service postings?** The Saga pattern — each service posts its own ledger entries; if downstream fails, a compensating reversal entry is posted, not a DB rollback
- **What is the outbox pattern here?** INSERT into `outbox_events` table in the same DB transaction as the journal entries. A separate relay job reads the outbox and publishes to Kafka. Guarantees Kafka gets the event if and only if the DB commit succeeded
