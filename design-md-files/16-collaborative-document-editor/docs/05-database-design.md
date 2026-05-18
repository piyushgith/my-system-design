# 05 — Database Design

## Objective
Define the storage schema, partitioning strategy, indexing plan, and consistency model for the Collaborative Document Editor. Distinguish between the hot (operational) path and cold (historical) path.

---

## Storage Technology Selection

| Data Type | Technology | Justification |
|---|---|---|
| Document metadata, permissions, comments | PostgreSQL | Relational integrity, ACID, strong consistency for critical metadata |
| Op log (source of truth event store) | Kafka + PostgreSQL op_log table | Kafka for streaming; PostgreSQL for queryable durable storage with offset indexing |
| Document snapshots (materialized state) | Amazon S3 / GCS | Binary blobs; cheap storage; immutable |
| Hot document state (active docs in memory) | Redis | Sub-millisecond reads; LRU eviction; active session state |
| Presence data | Redis only (ephemeral) | Presence is inherently ephemeral; no persistence needed |
| Full-text search | Elasticsearch | Inverted index; rich query support; separate from operational store |
| Export artifacts | S3 | Large binary outputs; pre-signed URL delivery |

---

## PostgreSQL Schema

### documents
```sql
CREATE TABLE documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id),
    owner_id        UUID NOT NULL REFERENCES users(id),
    title           TEXT NOT NULL DEFAULT '',
    schema_version  INT NOT NULL DEFAULT 1,
    latest_seq      BIGINT NOT NULL DEFAULT 0,
    latest_snapshot_id UUID REFERENCES snapshots(id),
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_documents_workspace ON documents(workspace_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_documents_owner ON documents(owner_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_documents_updated ON documents(updated_at DESC) WHERE is_deleted = FALSE;
```

**Soft delete strategy:** `is_deleted` flag with `deleted_at` timestamp. Hard delete runs as a background job 30 days after soft delete (after GDPR purge window).

---

### op_log
The op_log is the event store. It is append-only. Rows are never updated or deleted (except by GDPR erasure, which replaces content with a tombstone marker).

```sql
CREATE TABLE op_log (
    id              UUID NOT NULL DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL,
    seq             BIGINT NOT NULL,
    author_id       UUID NOT NULL,
    operation       JSONB NOT NULL,    -- serialized Operation value object
    parent_seq      BIGINT NOT NULL,   -- seq this op was based on (for OT provenance)
    applied_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id       UUID NOT NULL,     -- WS session ID for dedup
    client_seq      INT NOT NULL,      -- client-side sequence for dedup
    PRIMARY KEY (document_id, seq)     -- composite PK for range queries
) PARTITION BY RANGE (seq);            -- or by document_id for large shards
```

**Partitioning strategy:**
- Partition `op_log` by `document_id` range (hash partitioning) for hot documents
- For time-based archival: secondary partition by `applied_at` month after document goes cold
- Hot partition (active docs, last 30 days): PostgreSQL with SSD-backed storage
- Warm partition (30–365 days): PostgreSQL with standard storage
- Cold archive (1 year+): Export to Parquet on S3; served by Athena for historical queries

**Critical indexes:**
```sql
CREATE INDEX idx_op_log_doc_seq ON op_log(document_id, seq);         -- range scan for replay
CREATE UNIQUE INDEX idx_op_log_dedup ON op_log(document_id, client_id, client_seq); -- dedup
```

**Why JSONB for operation?** The operation schema evolves (new op types added for tables, embeds, etc.). JSONB allows schema evolution without migration. For performance-critical fields (seq, author_id), they are extracted as columns. JSONB querying on operation type is used by analytics, not the hot path.

---

### snapshots
```sql
CREATE TABLE snapshots (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES documents(id),
    at_seq          BIGINT NOT NULL,
    s3_key          TEXT NOT NULL,
    size_bytes      BIGINT,
    content_hash    CHAR(64),          -- SHA-256 for integrity verification
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (document_id, at_seq)
);

CREATE INDEX idx_snapshots_doc_seq ON snapshots(document_id, at_seq DESC);
```

---

### revisions (named versions)
```sql
CREATE TABLE revisions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES documents(id),
    at_seq          BIGINT NOT NULL,
    name            TEXT NOT NULL,
    description     TEXT,
    created_by      UUID NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

### permissions
```sql
CREATE TABLE permissions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES documents(id),
    principal_type  TEXT NOT NULL CHECK (principal_type IN ('user','group','link','workspace')),
    principal_id    UUID,              -- null for link-type principals
    link_token      TEXT UNIQUE,       -- for link-type principals
    access_level    TEXT NOT NULL CHECK (access_level IN ('owner','editor','commenter','viewer')),
    granted_by      UUID NOT NULL REFERENCES users(id),
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ,
    is_revoked      BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at      TIMESTAMPTZ
);

CREATE INDEX idx_permissions_doc_principal ON permissions(document_id, principal_id)
    WHERE is_revoked = FALSE AND (expires_at IS NULL OR expires_at > NOW());
CREATE INDEX idx_permissions_link ON permissions(link_token)
    WHERE principal_type = 'link' AND is_revoked = FALSE;
```

---

### comments
```sql
CREATE TABLE comments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES documents(id),
    author_id       UUID NOT NULL REFERENCES users(id),
    anchor_start    INT NOT NULL,      -- character offset at creation
    anchor_end      INT NOT NULL,
    anchor_seq      BIGINT NOT NULL,   -- seq at time of anchoring
    current_start   INT,               -- transformed anchor (updated by transform pipeline)
    current_end     INT,
    body            TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','resolved')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ,
    resolved_by     UUID REFERENCES users(id)
);

CREATE TABLE comment_replies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    comment_id      UUID NOT NULL REFERENCES comments(id),
    author_id       UUID NOT NULL REFERENCES users(id),
    body            TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_comments_document ON comments(document_id, created_at DESC) WHERE status = 'open';
```

---

## Redis Data Structures

### Document Hot Cache
```
Key: doc:snapshot:{documentId}
Type: String (serialized JSON/binary)
TTL: 10 minutes (refreshed on access)
Value: Snapshot content + at_seq

Key: doc:ops:{documentId}:{afterSeq}
Type: Sorted Set (score = seq, value = serialized op)
TTL: 5 minutes
```

### Permission Cache
```
Key: perm:{principalId}:{documentId}
Type: String
TTL: 60 seconds
Value: access_level enum string
```

### Presence
```
Key: presence:{documentId}
Type: Hash (field = userId, value = JSON PresenceData)
TTL: 30 seconds (refreshed by heartbeat)
```

### Active Session Counter
```
Key: sessions:{documentId}
Type: HyperLogLog (cardinality estimate for "N users editing")
TTL: 5 minutes
```

### Idempotency Store
```
Key: idempotency:{clientId}:{idempotencyKey}
Type: String (serialized response)
TTL: 24 hours
```

---

## Sharding Considerations

### PostgreSQL Sharding
- Primary shard key: `document_id` (UUID)
- Use Citus (PostgreSQL extension) for horizontal sharding of `op_log` and `documents`
- Shard placement follows workspace: all documents in a workspace co-located on same shard group (for efficient workspace-level queries)
- Cross-shard queries (global search) are avoided by routing through Elasticsearch

### Hot Document Isolation
- Documents receiving > 1,000 ops/sec are moved to a dedicated PostgreSQL instance ("hot shard") automatically
- Hot shard detection: monitor op_log insert rate per document; trigger migration at threshold
- This prevents a viral document from affecting all other users on the shared shard

---

## Indexing Strategy

| Table | Index | Query Pattern |
|---|---|---|
| `documents` | `(workspace_id, updated_at DESC)` | "My recent documents" list |
| `op_log` | `(document_id, seq)` | Op replay (the most critical query) |
| `op_log` | `(document_id, client_id, client_seq)` UNIQUE | Deduplication on retry |
| `snapshots` | `(document_id, at_seq DESC)` | Latest snapshot lookup |
| `permissions` | `(document_id, principal_id)` | Permission check |
| `comments` | `(document_id, status)` | Open comments per document |

---

## Consistency Tradeoffs

| Concern | Approach |
|---|---|
| Op ordering | Op log uses `seq` assigned by Collaboration Service (single authority per doc); PostgreSQL unique constraint enforces no duplicate seq |
| Snapshot consistency | Snapshot records snapshot offset; any read replays ops from `(snapshot.at_seq, latest_seq]` — this window must be consistent |
| Permission enforcement | Read-your-writes within same session via primary read for permission changes; cached reads after TTL |
| Cross-document atomicity | Not required; each document is its own aggregate |

---

## Data Archival Strategy

| Age | Storage | Access Pattern |
|---|---|---|
| 0–7 days | PostgreSQL primary + replica (SSD) | Hot read/write |
| 7–90 days | PostgreSQL replica (standard) | Read-mostly |
| 90 days–2 years | Cold PostgreSQL + S3 snapshots | Rare reads; S3 for bulk |
| 2+ years | Parquet on S3 + Athena | Compliance queries only |

**GDPR Purge:** When a user requests deletion, their `author_id` in op_log is replaced with a tombstone UUID. The content of their specific insert ops is nulled. This preserves document integrity (other ops are not removed) while erasing personal data. A background job processes purge requests asynchronously.

---

## Interview Discussion Points
- Why is op_log partitioned by document_id rather than by time?
- What is the risk of using JSONB for operation payloads, and how would you handle schema evolution for ops created 5 years ago?
- How does the snapshot + op replay approach behave when the snapshot is stale by 10,000 ops?
- At what point should you move from PostgreSQL + Citus to a purpose-built event store like EventStoreDB?
- How do you handle the case where two replicas disagree on the latest seq for a document during a network partition?
