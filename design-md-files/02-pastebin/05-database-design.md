# 05 — Database Design: Pastebin / Code Sharing Platform

---

## Objective

Design the relational data model for the Pastebin platform. Cover table schemas, indexing strategy, partitioning, sharding considerations, content storage routing, and data lifecycle management.

---

## Database Choice: PostgreSQL

### Why PostgreSQL?

| Feature | Benefit |
|---------|---------|
| JSONB columns | Flexible metadata (language hints, custom settings) without schema changes |
| Partial indexes | Index only non-deleted pastes — dramatically smaller index size |
| Row-level locking | Fine-grained concurrent access control |
| Read replicas | Horizontal read scaling with PostgreSQL streaming replication |
| Extensions | `pg_crypto` for UUID generation, `pg_trgm` for trigram search |
| CTEs and Window Functions | Complex analytics queries in a single statement |
| LISTEN/NOTIFY | Lightweight event mechanism for cleanup trigger (alternative to polling) |

### Why NOT a Document DB (MongoDB) for metadata?
- Paste metadata is highly relational (user→paste, paste→version)
- ACID transactions needed for custom alias uniqueness
- PostgreSQL JSONB handles the occasional flexible field just as well

### Why NOT store paste content in PostgreSQL TEXT columns?
- Text columns in PostgreSQL are stored inline in the heap (up to 1 GB per row)
- TOAST mechanism handles large text, but it bloats the table
- Table vacuum, EXPLAIN queries, and index operations suffer with large text columns
- Object storage (S3) is orders of magnitude cheaper for bulk content
- S3 scales to unlimited size; PostgreSQL TABLE_AM has practical limits

**Rule used:** Content < 1 KB → inline DB; Content ≥ 1 KB → S3 reference.

---

## Schema: paste.* (Paste Management Context)

### Table: `pastes`

```sql
CREATE TABLE paste.pastes (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    short_key       VARCHAR(16)     NOT NULL,
    title           VARCHAR(255),
    language        VARCHAR(64)     NOT NULL DEFAULT 'plaintext',

    -- Content storage
    content_type    VARCHAR(16)     NOT NULL CHECK (content_type IN ('INLINE', 'S3')),
    content_inline  TEXT,                           -- non-null when content_type = INLINE
    content_s3_key  VARCHAR(512),                   -- non-null when content_type = S3
    content_size    BIGINT          NOT NULL,        -- bytes
    content_hash    CHAR(64)        NOT NULL,        -- SHA-256 hex

    -- Lifecycle
    expires_at      TIMESTAMPTZ,                    -- NULL means NEVER
    is_deleted      BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMPTZ,
    deletion_reason VARCHAR(32),                    -- USER_REQUESTED | EXPIRED | ABUSE

    -- Access control
    access_level    VARCHAR(16)     NOT NULL DEFAULT 'PUBLIC'
                    CHECK (access_level IN ('PUBLIC', 'UNLISTED', 'PRIVATE')),
    password_hash   VARCHAR(128),                   -- bcrypt hash, nullable

    -- Ownership
    owner_id        UUID,                           -- NULL for anonymous
    is_abuse_flagged BOOLEAN        NOT NULL DEFAULT FALSE,

    -- Stats (denormalized, updated async)
    view_count      BIGINT          NOT NULL DEFAULT 0,

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
```

### Indexes on `pastes`

```sql
-- Primary lookup: short_key resolution (most common query)
CREATE UNIQUE INDEX idx_pastes_short_key
    ON paste.pastes (short_key)
    WHERE is_deleted = FALSE;

-- Expiry cleanup: find pastes to expire (polled every minute)
CREATE INDEX idx_pastes_expires_at
    ON paste.pastes (expires_at)
    WHERE expires_at IS NOT NULL AND is_deleted = FALSE;

-- User paste list: fetch by owner, sorted by creation
CREATE INDEX idx_pastes_owner_created
    ON paste.pastes (owner_id, created_at DESC)
    WHERE is_deleted = FALSE AND owner_id IS NOT NULL;

-- Content deduplication: find existing paste with same hash
CREATE INDEX idx_pastes_content_hash
    ON paste.pastes (content_hash)
    WHERE is_deleted = FALSE AND content_type = 'S3';

-- Abuse: find flagged pastes for moderation queue
CREATE INDEX idx_pastes_abuse_flagged
    ON paste.pastes (created_at DESC)
    WHERE is_abuse_flagged = TRUE AND is_deleted = FALSE;
```

**Partial index design note:** All key indexes are **partial indexes** (`WHERE is_deleted = FALSE`). This keeps indexes small and fast — deleted pastes (which are the majority over time) are excluded. Query plans automatically use these indexes for the common active-paste lookup pattern.

---

### Table: `paste_versions`

Stores immutable version history for pastes that support editing (V2 feature).

```sql
CREATE TABLE paste.paste_versions (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    paste_id        UUID            NOT NULL REFERENCES paste.pastes(id) ON DELETE CASCADE,
    version_number  INT             NOT NULL,
    content_type    VARCHAR(16)     NOT NULL,
    content_inline  TEXT,
    content_s3_key  VARCHAR(512),
    content_size    BIGINT          NOT NULL,
    content_hash    CHAR(64)        NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    UNIQUE (paste_id, version_number)
);

CREATE INDEX idx_paste_versions_paste_id
    ON paste.paste_versions (paste_id, version_number DESC);
```

---

### Table: `expiry_schedule`

Tracks pending expiry events for the cleanup job.

```sql
CREATE TABLE paste.expiry_schedule (
    paste_id    UUID            PRIMARY KEY REFERENCES paste.pastes(id),
    expires_at  TIMESTAMPTZ     NOT NULL,
    processed   BOOLEAN         NOT NULL DEFAULT FALSE,
    processed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_expiry_schedule_pending
    ON paste.expiry_schedule (expires_at)
    WHERE processed = FALSE;
```

**Why a separate table?** `pastes.expires_at` could be used directly, but a separate `expiry_schedule` table:
1. Avoids locking the main `pastes` table for cleanup polls
2. Makes it easy to mark individual expirations as processed (separate from the main paste row)
3. Can be purged independently without affecting the paste record

---

### Table: `idempotency_keys`

Stores idempotency keys for paste creation (alternative to Redis for durability).

```sql
CREATE TABLE paste.idempotency_keys (
    key             VARCHAR(128)    PRIMARY KEY,
    paste_id        UUID            NOT NULL,
    response_body   JSONB           NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW() + INTERVAL '24 hours'
);
```

**Note:** For MVP, Redis is preferred for idempotency keys (lower latency, TTL-based cleanup). This table is the fallback if Redis is unavailable.

---

## Schema: identity.* (Identity Context)

### Table: `identity.users`

```sql
CREATE TABLE identity.users (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255)    NOT NULL,
    email_verified  BOOLEAN         NOT NULL DEFAULT FALSE,
    password_hash   VARCHAR(128),                   -- NULL for OAuth-only users
    display_name    VARCHAR(100),
    avatar_url      VARCHAR(512),
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_users_email ON identity.users (LOWER(email));
```

### Table: `identity.api_keys`

```sql
CREATE TABLE identity.api_keys (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID            NOT NULL REFERENCES identity.users(id),
    key_hash        CHAR(64)        NOT NULL,        -- SHA-256 of the raw key
    key_prefix      VARCHAR(16)     NOT NULL,        -- First 8 chars for display
    name            VARCHAR(100),
    last_used_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_api_keys_hash ON identity.api_keys (key_hash);
CREATE INDEX idx_api_keys_user ON identity.api_keys (user_id) WHERE is_active = TRUE;
```

---

## Schema: analytics.* (Analytics Context)

### Table: `analytics.paste_view_events`

```sql
CREATE TABLE analytics.paste_view_events (
    id              BIGSERIAL       PRIMARY KEY,
    paste_id        UUID            NOT NULL,       -- No FK — cross-context reference
    viewer_hash     CHAR(64),                       -- SHA-256(IP+date) for uniqueness
    viewed_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    is_authenticated BOOLEAN        NOT NULL DEFAULT FALSE
)
PARTITION BY RANGE (viewed_at);

-- Monthly partitions
CREATE TABLE analytics.paste_view_events_2026_05
    PARTITION OF analytics.paste_view_events
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
```

**Partitioned by time** because:
- Old event data can be archived/dropped by dropping old partitions (no DELETE needed)
- Analytics queries almost always have a time range filter — partition pruning improves performance dramatically
- `viewed_at` index is effective within each monthly partition

### Table: `analytics.paste_stats`

```sql
CREATE TABLE analytics.paste_stats (
    paste_id        UUID            PRIMARY KEY,
    total_views     BIGINT          NOT NULL DEFAULT 0,
    unique_views    BIGINT          NOT NULL DEFAULT 0,
    last_updated    TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
```

---

## ER Diagram

```mermaid
erDiagram
    USERS {
        uuid id PK
        varchar email UK
        varchar password_hash
        varchar display_name
        boolean is_active
        timestamptz created_at
    }

    API_KEYS {
        uuid id PK
        uuid user_id FK
        char key_hash UK
        varchar name
        timestamptz last_used_at
        boolean is_active
    }

    PASTES {
        uuid id PK
        varchar short_key UK
        varchar title
        varchar language
        varchar content_type
        text content_inline
        varchar content_s3_key
        bigint content_size
        char content_hash
        timestamptz expires_at
        boolean is_deleted
        varchar access_level
        varchar password_hash
        uuid owner_id FK
        bigint view_count
        timestamptz created_at
    }

    PASTE_VERSIONS {
        uuid id PK
        uuid paste_id FK
        int version_number
        varchar content_type
        text content_inline
        varchar content_s3_key
        timestamptz created_at
    }

    EXPIRY_SCHEDULE {
        uuid paste_id PK_FK
        timestamptz expires_at
        boolean processed
    }

    USERS ||--o{ API_KEYS : "has"
    USERS ||--o{ PASTES : "owns"
    PASTES ||--o{ PASTE_VERSIONS : "has versions"
    PASTES ||--o| EXPIRY_SCHEDULE : "scheduled for"
```

---

## Short Key Generation Strategy

### Option A: Random Base62 (Simple, Chosen for MVP)

```
Key space: 62^6 = 56 billion keys (6-char)
Collision probability at 10M pastes: negligible (~0.018%)
Algorithm:
  1. Generate 6 random Base62 chars
  2. INSERT with UNIQUE constraint
  3. On conflict: retry with 7 chars, then 8 chars
```

**Problem:** At 100M+ pastes, collision rate increases. Retry logic becomes common.

### Option B: Counter-Based (Preferred for Scale)

```
Algorithm:
  1. Increment global atomic counter (PostgreSQL SEQUENCE or Redis INCR)
  2. Encode counter as Base62: counter 1000000 → "4c92"
  3. No collision possible — counter guarantees uniqueness
```

**Problem:** Counter reveals information (paste volume, ordering). Mitigate by:
- Starting counter at a large random offset (e.g., 1,000,000)
- XOR-shuffling the counter bits before encoding

### Option C: Snowflake-style Distributed ID

For multi-node setups: timestamp (41 bits) + node ID (10 bits) + sequence (12 bits) → globally unique 63-bit integer → Base62 encode.

**Chosen strategy:** Option B (counter-based) for MVP. Migrate to Option C when horizontally scaling write nodes.

---

## Indexing Strategy Summary

| Index | Table | Purpose | Type |
|-------|-------|---------|------|
| `idx_pastes_short_key` | pastes | Primary key lookup by URL | Unique partial |
| `idx_pastes_expires_at` | pastes | Expiry cleanup polling | Partial (not deleted) |
| `idx_pastes_owner_created` | pastes | User paste list | Composite partial |
| `idx_pastes_content_hash` | pastes | Deduplication check | Partial (S3 type only) |
| `idx_users_email` | users | Login lookup | Unique functional (LOWER) |
| `idx_api_keys_hash` | api_keys | API key authentication | Unique |
| `idx_expiry_schedule_pending` | expiry_schedule | Cleanup job poll | Partial (not processed) |

---

## Partitioning Strategy

### `analytics.paste_view_events` — Range Partition by Month

- New partition created automatically (pg_partman or application code)
- Partitions older than 12 months moved to cold storage or dropped
- Query `WHERE viewed_at BETWEEN x AND y` enables partition pruning

### `paste.pastes` — No Partitioning at MVP

At 10M pastes, a single table with partial indexes performs well. Partitioning adds management overhead for marginal gain.

**Trigger for partitioning:** Table exceeds 100M rows or query latency on `expires_at` index exceeds 50ms. Then: range partition by `created_at` (monthly).

---

## Data Lifecycle Management

### Soft Delete
- `is_deleted = TRUE` + `deleted_at` + `deletion_reason`
- Deleted pastes excluded from all normal queries via partial indexes
- Soft-deleted rows are physically removed via background cleanup job after 30 days

### Hard Delete (Background Job)
```sql
DELETE FROM paste.pastes
WHERE is_deleted = TRUE AND deleted_at < NOW() - INTERVAL '30 days';
```

### S3 Lifecycle Policy
```
Rule: transition_to_infrequent_access
  - Age: > 90 days
  - Storage class: S3-IA (cheaper for infrequently accessed)

Rule: transition_to_glacier
  - Age: > 365 days
  - For NEVER-expire pastes only

Rule: expire_objects
  - Age: determined per-object via object tagging
  - Tag: expires_at={ISO8601}
  - S3 lifecycle rule deletes objects past their expiry date
```

### Audit Trail
- Deletion events are published to Kafka (`paste.deleted` topic)
- Analytics context consumes and stores in append-only `deletion_log` table
- Retained for 7 years (compliance)

---

## Read Replica Usage

| Query Type | Routed To |
|-----------|-----------|
| `GET /pastes/{key}` (hot path) | Redis → Primary (cache miss) |
| `GET /users/me/pastes` (list) | Read Replica |
| Analytics dashboard queries | Read Replica |
| Expiry cleanup poll | Primary (needs consistent view) |
| Write operations | Primary only |

---

## Consistency Tradeoffs

| Scenario | Consistency Level | Reasoning |
|---------|------------------|-----------|
| Paste content | Strong | User must see their paste immediately after creation |
| View count | Eventual | Acceptable lag of minutes; avoids write hotspot |
| User paste list | Slightly stale OK | Read from replica, lag < 10ms typical |
| Expiry | Eventual | Paste expires within minutes, not exactly at expiry second |
| Deduplication | Strong | Two concurrent identical pastes must not create duplicate S3 objects |

---

## Interview Discussion Points

- How does the partial index on `is_deleted = FALSE` prevent index bloat as the system accumulates millions of deleted rows?
- What happens to the short_key unique index when a paste is soft-deleted — can the same key be reused?
- Why is view count stored both in `pastes` (denormalized) and in `paste_stats` (analytics)?
- How do you handle concurrent paste creation with the same custom alias?
- At what scale (rows) would you introduce table partitioning, and what column would you partition on?
- How do you recover if the S3 delete for an expired paste fails? Is the paste now in an inconsistent state?
