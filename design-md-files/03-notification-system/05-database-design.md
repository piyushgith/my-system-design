# 05 — Database Design: Notification System

---

## Objective

Design the complete data storage layer for the Notification System. Cover table schemas, partitioning, indexing, data lifecycle, and the rationale for using different storage engines for different concerns.

---

## Storage Technology Choices

| Data Type | Storage | Justification |
|-----------|---------|---------------|
| Notification metadata | PostgreSQL (partitioned) | Transactional, relational, strong consistency for Outbox pattern |
| Delivery attempt log | PostgreSQL (range-partitioned by date) | High-write, time-series-like, needs partitioned pruning |
| User preferences | PostgreSQL + Redis cache | Read on every dispatch; cache mandatory |
| Templates | PostgreSQL + Redis cache | Small dataset, read-heavy, immutable versions |
| In-App inbox | PostgreSQL | Per-user access, indexed by user_id |
| Batch campaigns | PostgreSQL | CRUD with status tracking |
| Idempotency keys | Redis (TTL 24h) | High-volume, ephemeral, no persistence needed |
| Delivery analytics | ClickHouse | Columnar, aggregation-optimized, not on delivery critical path |
| Retry state | Kafka (retry topics) + Redis | Retry metadata lives in the message itself |

---

## PostgreSQL Schema

### Table: `notification_requests`

```sql
CREATE TABLE notification_requests (
    notification_id     UUID            NOT NULL,
    idempotency_key     VARCHAR(255)    NOT NULL,
    category            VARCHAR(30)     NOT NULL,   -- TRANSACTIONAL, MARKETING, etc.
    priority            VARCHAR(20)     NOT NULL,   -- CRITICAL, HIGH, NORMAL, LOW
    template_id         VARCHAR(100)    NOT NULL,
    template_version    INTEGER         NOT NULL,
    recipient_user_id   UUID            NOT NULL,
    batch_id            UUID,
    variables           JSONB           NOT NULL DEFAULT '{}',
    channels_override   TEXT[],                     -- NULL = use preferences
    status              VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    scheduled_at        TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    dispatched_at       TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    producer_service    VARCHAR(100),
    producer_trace_id   VARCHAR(255),
    CONSTRAINT pk_notification PRIMARY KEY (notification_id, created_at)
) PARTITION BY RANGE (created_at);

-- Monthly partitions
CREATE TABLE notification_requests_2026_05
    PARTITION OF notification_requests
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE UNIQUE INDEX uidx_notification_idempotency
    ON notification_requests (idempotency_key);

CREATE INDEX idx_notification_user_status
    ON notification_requests (recipient_user_id, status, created_at DESC);

CREATE INDEX idx_notification_batch
    ON notification_requests (batch_id, created_at DESC)
    WHERE batch_id IS NOT NULL;

CREATE INDEX idx_notification_scheduled
    ON notification_requests (scheduled_at)
    WHERE status = 'PENDING' AND scheduled_at IS NOT NULL;
```

**Partition Strategy:**
- Monthly range partitions by `created_at`
- Each partition holds ~1.65 billion rows at the projected scale (55M/day × 30 days) — further sub-partitioning if needed
- Old partitions (> 90 days) detached and archived to S3 as Parquet
- Partition pruning ensures queries on recent data don't scan historical partitions

---

### Table: `delivery_attempts`

```sql
CREATE TABLE delivery_attempts (
    attempt_id              UUID            NOT NULL,
    notification_id         UUID            NOT NULL,
    channel                 VARCHAR(20)     NOT NULL,  -- EMAIL, SMS, PUSH, IN_APP
    provider                VARCHAR(50)     NOT NULL,
    provider_message_id     VARCHAR(255),
    status                  VARCHAR(20)     NOT NULL,  -- PENDING, IN_FLIGHT, DELIVERED, FAILED, BOUNCED
    attempt_number          SMALLINT        NOT NULL DEFAULT 1,
    attempted_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    delivered_at            TIMESTAMPTZ,
    failure_reason          VARCHAR(500),
    failure_code            VARCHAR(50),
    next_retry_at           TIMESTAMPTZ,
    CONSTRAINT pk_delivery_attempt PRIMARY KEY (attempt_id, attempted_at)
) PARTITION BY RANGE (attempted_at);

CREATE TABLE delivery_attempts_2026_05
    PARTITION OF delivery_attempts
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE INDEX idx_delivery_notification
    ON delivery_attempts (notification_id, channel, attempted_at DESC);

CREATE INDEX idx_delivery_retry
    ON delivery_attempts (next_retry_at)
    WHERE status = 'FAILED' AND next_retry_at IS NOT NULL;

CREATE INDEX idx_delivery_provider_ref
    ON delivery_attempts (provider_message_id)
    WHERE provider_message_id IS NOT NULL;
```

**Design Notes:**
- `attempt_id` + `attempted_at` composite PK for partition alignment
- The `idx_delivery_retry` partial index is small and fast — only failed rows with a scheduled retry
- `provider_message_id` index supports webhook callbacks from providers (e.g., Twilio sends SMS delivery receipt using its SID)

---

### Table: `user_notification_preferences`

```sql
CREATE TABLE user_notification_preferences (
    user_id                 UUID            NOT NULL,
    channel                 VARCHAR(20)     NOT NULL,
    category                VARCHAR(30)     NOT NULL,
    opted_in                BOOLEAN         NOT NULL DEFAULT TRUE,
    quiet_hours_start       TIME,
    quiet_hours_end         TIME,
    timezone                VARCHAR(50)     DEFAULT 'UTC',
    unsubscribe_token       VARCHAR(64)     UNIQUE,
    hard_unsubscribed       BOOLEAN         NOT NULL DEFAULT FALSE,
    hard_unsubscribed_at    TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_user_preference PRIMARY KEY (user_id, channel, category)
);

CREATE INDEX idx_preference_unsubscribe_token
    ON user_notification_preferences (unsubscribe_token)
    WHERE unsubscribe_token IS NOT NULL;
```

**Caching:** Full preference set for a user is cached in Redis as a serialized hash with key `pref:{user_id}`, TTL 1 hour. Updated via write-through on any PATCH request.

---

### Table: `templates`

```sql
CREATE TABLE templates (
    template_id             VARCHAR(100)    NOT NULL,
    version                 INTEGER         NOT NULL DEFAULT 1,
    channel                 VARCHAR(20)     NOT NULL,
    locale                  VARCHAR(10)     NOT NULL DEFAULT 'en-US',
    subject                 VARCHAR(500),
    body_html               TEXT,
    body_text               TEXT            NOT NULL,
    push_title              VARCHAR(100),
    push_body               VARCHAR(255),
    variables_schema        JSONB           NOT NULL DEFAULT '{}',
    is_active               BOOLEAN         NOT NULL DEFAULT TRUE,
    created_by              VARCHAR(100),
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    deprecated_at           TIMESTAMPTZ,
    CONSTRAINT pk_template PRIMARY KEY (template_id, version, channel, locale)
);

CREATE INDEX idx_template_active
    ON templates (template_id, channel, locale)
    WHERE is_active = TRUE;
```

**Caching:** Templates are cached in Redis with key `tpl:{template_id}:{version}:{channel}:{locale}`, TTL 24 hours. Invalidated on update.

---

### Table: `batch_campaigns`

```sql
CREATE TABLE batch_campaigns (
    batch_id                UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    VARCHAR(255)    NOT NULL,
    template_id             VARCHAR(100)    NOT NULL,
    template_version        INTEGER         NOT NULL,
    segment_id              UUID            NOT NULL,
    estimated_recipients    INTEGER,
    actual_recipients       INTEGER         DEFAULT 0,
    dispatched_count        INTEGER         DEFAULT 0,
    delivered_count         INTEGER         DEFAULT 0,
    failed_count            INTEGER         DEFAULT 0,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    throttle_rps            INTEGER         DEFAULT 5000,
    scheduled_at            TIMESTAMPTZ,
    started_at              TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    created_by              VARCHAR(100),
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_campaign_status ON batch_campaigns (status, scheduled_at)
    WHERE status IN ('SCHEDULED', 'RUNNING');
```

---

### Table: `in_app_inbox`

```sql
CREATE TABLE in_app_inbox (
    inbox_item_id           UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id         UUID            NOT NULL,
    user_id                 UUID            NOT NULL,
    title                   VARCHAR(255)    NOT NULL,
    body                    TEXT            NOT NULL,
    action_url              VARCHAR(2048),
    image_url               VARCHAR(2048),
    is_read                 BOOLEAN         NOT NULL DEFAULT FALSE,
    read_at                 TIMESTAMPTZ,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW() + INTERVAL '30 days'
);

CREATE INDEX idx_inbox_user_unread
    ON in_app_inbox (user_id, created_at DESC)
    WHERE is_read = FALSE;

CREATE INDEX idx_inbox_user_all
    ON in_app_inbox (user_id, created_at DESC);

CREATE INDEX idx_inbox_expiry
    ON in_app_inbox (expires_at)
    WHERE expires_at < NOW();
```

**TTL Cleanup:** A scheduled job runs nightly to DELETE FROM `in_app_inbox` WHERE `expires_at < NOW()`. Partial index on `expires_at` makes this scan efficient.

---

### Outbox Table (for Transactional Outbox Pattern)

```sql
CREATE TABLE outbox_events (
    event_id                UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type          VARCHAR(50)     NOT NULL,  -- 'notification_request'
    aggregate_id            UUID            NOT NULL,
    event_type              VARCHAR(100)    NOT NULL,  -- 'notification.requested'
    payload                 JSONB           NOT NULL,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    published_at            TIMESTAMPTZ,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX idx_outbox_pending
    ON outbox_events (created_at)
    WHERE status = 'PENDING';
```

---

## Partitioning Strategy

| Table | Strategy | Partition Key | Partition Size | Retention |
|-------|---------|--------------|----------------|-----------|
| notification_requests | RANGE | created_at (monthly) | ~55 GB/month | 90 days hot, archive after |
| delivery_attempts | RANGE | attempted_at (monthly) | ~50 GB/month | 90 days hot, archive after |
| in_app_inbox | None (user-partitioned logically) | user_id | ~50 GB total | 30-day TTL per row |

**Why RANGE over HASH partitioning?**
Range partitioning on `created_at` allows clean partition drop for archival (drop the whole month's partition), efficient range scans for monitoring queries ("all failed deliveries in the last 24 hours"), and partition pruning for queries with date filters.

---

## Indexing Strategy

### Key Indexes and Their Purpose

| Index | Purpose | Expected Selectivity |
|-------|---------|---------------------|
| `uidx_notification_idempotency` | Idempotency check on every write | High (unique) |
| `idx_notification_user_status` | Producer querying their user's notifications | Medium |
| `idx_notification_scheduled` | Scheduler polling for due jobs | Very selective (partial) |
| `idx_delivery_notification` | Status page: delivery attempts per notification | Medium |
| `idx_delivery_retry` | Retry worker polling for due retries | Selective (partial, small set) |
| `idx_delivery_provider_ref` | Provider webhook lookup | High (unique per provider) |
| `idx_inbox_user_unread` | User inbox unread feed | High (per user) |
| `idx_outbox_pending` | Outbox relay polling | Selective (partial) |

### Index Anti-Patterns to Avoid
- No index on `status` alone — status has low cardinality (5 values across 55M rows/day = full scan)
- No index on `category` alone — same problem
- All status-based indexes are partial (WHERE clause) to limit index size

---

## Sharding Considerations

At the projected scale (55M notifications/day), PostgreSQL with partitioning handles the load on adequate hardware (32-core, 256 GB RAM). Sharding becomes necessary when:

- Single node write throughput exceeds ~50K writes/sec
- Data volume exceeds 10 TB in the hot tier
- A single user's notification history becomes disproportionately large (high-volume accounts)

**Sharding Key:** `recipient_user_id` — routes all queries for a user to the same shard. Enables efficient per-user queries without scatter-gather. Downside: uneven distribution if some users receive far more notifications (use consistent hashing to mitigate hotspots).

---

## Data Lifecycle and Archival

| Data | Hot Retention | Action |
|------|-------------|--------|
| notification_requests | 90 days in PostgreSQL | Drop partition, export to S3 as Parquet |
| delivery_attempts | 90 days in PostgreSQL | Drop partition, export to S3 as Parquet |
| in_app_inbox rows | 30 days | Row-level DELETE via scheduled job |
| Idempotency keys | 24 hours | Redis TTL auto-expires |
| Templates | Permanent | Soft delete only |
| Preferences | Permanent | Soft delete on account deletion |
| ClickHouse delivery log | 1 year raw, permanent aggregates | Tiered storage after 1 year |

---

## Multi-Tenancy Considerations

If the Notification System is a shared platform across multiple products (Product A, Product B):
- Add `tenant_id` column to all primary tables
- Partition within a tenant shard by `created_at`
- Row-Level Security (RLS) in PostgreSQL enforces tenant isolation
- Each tenant gets their own set of templates and preferences (namespace by tenant_id)

---

## Audit and History Strategy

- `delivery_attempts` is the append-only audit trail for all delivery events
- `outbox_events` is never deleted — captures the exact intent at submission time
- Soft delete on `templates` (is_active = false) — no hard deletes
- `user_notification_preferences` keeps `updated_at` for change tracking; a `preferences_audit` table captures history for compliance:

```sql
CREATE TABLE preferences_audit (
    audit_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    channel         VARCHAR(20),
    category        VARCHAR(30),
    previous_value  JSONB,
    new_value       JSONB,
    changed_by      VARCHAR(100),  -- 'user', 'system', 'admin'
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## Interview Discussion Points

- Why is `delivery_attempts` a separate table from `notification_requests` instead of arrays/JSONB?
- At what point does the `notification_requests` table need sharding vs just better partitioning?
- How do you handle the Outbox table growing unboundedly if the relay falls behind?
- What are the implications of storing variables (potentially containing PII) in JSONB on notification_requests?
- How does range partitioning on `created_at` help with GDPR right-to-erasure requests (deleting user data)?
- Why is `delivery_attempts` never updated after being written? What invariants does this guarantee?
