# 05 — Database Design: CI/CD Platform

---

## Objective

Define the relational database schema, indexing strategy, partitioning, data retention, and storage topology for the CI/CD platform. Covers the control plane (PostgreSQL), ephemeral buffer (Redis), and large object storage (S3).

---

## Storage Topology

| Data Type | Store | Rationale |
|---|---|---|
| Run/job/step state | PostgreSQL | ACID for state machine transitions |
| Secret values | PostgreSQL (encrypted) + KMS | Encrypted at rest, relational for access control |
| Log lines (live) | Redis (ring buffer) | Sub-millisecond append/read for streaming |
| Log files (complete) | S3 (gzipped) | Cheap, durable, scalable for large text |
| Artifacts | S3 | Large binary blobs, direct upload |
| Pipeline YAML cache | S3 + Redis | Immutable per commit SHA |
| Webhook events | PostgreSQL | Idempotency check + audit |
| Runner state | PostgreSQL | Heartbeat tracking, job assignment |
| Scheduler metadata | Redis + PostgreSQL | Queue state in Redis, persistent in PG |

---

## PostgreSQL Schema

### Organizations and Repositories

```sql
CREATE TABLE organizations (
    org_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) UNIQUE NOT NULL,
    plan            VARCHAR(20) NOT NULL DEFAULT 'FREE',
    concurrent_job_limit    INTEGER NOT NULL DEFAULT 10,
    artifact_quota_gb       INTEGER NOT NULL DEFAULT 10,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ
);

CREATE TABLE repositories (
    repo_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL REFERENCES organizations(org_id),
    name            VARCHAR(255) NOT NULL,
    git_url         TEXT NOT NULL,
    provider        VARCHAR(20) NOT NULL,  -- GITHUB, GITLAB, etc.
    default_branch  VARCHAR(255) DEFAULT 'main',
    webhook_secret  BYTEA,                 -- AES-256 encrypted
    access_token    BYTEA,                 -- AES-256 encrypted
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ,
    UNIQUE (org_id, name)
);

CREATE INDEX idx_repos_org ON repositories(org_id);
```

### Pipeline Runs

```sql
CREATE TABLE pipeline_runs (
    run_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL REFERENCES organizations(org_id),
    repo_id         UUID NOT NULL REFERENCES repositories(repo_id),
    workflow_name   VARCHAR(255) NOT NULL,
    branch          VARCHAR(255),
    commit_sha      VARCHAR(40),
    trigger_type    VARCHAR(50) NOT NULL,  -- PUSH, PR, MANUAL, CRON
    trigger_actor   VARCHAR(255),          -- user who triggered (for manual)
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    -- Run-level conclusion: SUCCEEDED, FAILED, CANCELLED, TIMED_OUT
    conclusion      VARCHAR(20),
    -- Denormalized for fast status queries
    jobs_total      INTEGER NOT NULL DEFAULT 0,
    jobs_succeeded  INTEGER NOT NULL DEFAULT 0,
    jobs_failed     INTEGER NOT NULL DEFAULT 0
);

-- Partition by created_at for retention management
CREATE TABLE pipeline_runs_2024_q1 PARTITION OF pipeline_runs
    FOR VALUES FROM ('2024-01-01') TO ('2024-04-01');

CREATE INDEX idx_runs_repo_status ON pipeline_runs(repo_id, status, created_at DESC);
CREATE INDEX idx_runs_org_created ON pipeline_runs(org_id, created_at DESC);
CREATE INDEX idx_runs_status_created ON pipeline_runs(status, created_at)
    WHERE status IN ('PENDING', 'QUEUED', 'IN_PROGRESS');  -- partial index for active runs
```

**Why partition by time?** Old runs are never updated — only read for history. Dropping a time partition is O(1) and doesn't lock the table. Queries for recent runs hit smaller partitions.

### Jobs

```sql
CREATE TABLE jobs (
    job_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id          UUID NOT NULL REFERENCES pipeline_runs(run_id),
    org_id          UUID NOT NULL,   -- denormalized for quota enforcement
    workflow_job_id VARCHAR(100) NOT NULL,  -- name in YAML: "build", "test"
    status          VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    runner_id       UUID,
    runner_label    VARCHAR(255),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    exit_code       INTEGER,
    log_s3_key      TEXT,
    matrix_index    INTEGER DEFAULT 0,
    matrix_values   JSONB,           -- {"os": "ubuntu", "node": "18"}
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_jobs_run ON jobs(run_id);
CREATE INDEX idx_jobs_status ON jobs(status, created_at)
    WHERE status IN ('QUEUED', 'WAITING', 'RUNNING');
CREATE INDEX idx_jobs_runner ON jobs(runner_id) WHERE runner_id IS NOT NULL;
```

### Steps

```sql
CREATE TABLE steps (
    step_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL REFERENCES jobs(job_id),
    step_index      INTEGER NOT NULL,
    name            VARCHAR(255),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    exit_code       INTEGER,
    log_start_line  INTEGER,
    log_end_line    INTEGER,
    UNIQUE (job_id, step_index)
);

CREATE INDEX idx_steps_job ON steps(job_id);
```

### Runners

```sql
CREATE TABLE runners (
    runner_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID REFERENCES organizations(org_id),  -- NULL = platform-managed
    name            VARCHAR(255) NOT NULL,
    runner_type     VARCHAR(20) NOT NULL,  -- MANAGED, SELF_HOSTED
    status          VARCHAR(20) NOT NULL DEFAULT 'IDLE',
    labels          TEXT[] NOT NULL DEFAULT '{}',
    current_job_id  UUID REFERENCES jobs(job_id),
    last_heartbeat  TIMESTAMPTZ DEFAULT NOW(),
    version         VARCHAR(50),
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_runners_status ON runners(status, labels);
CREATE INDEX idx_runners_heartbeat ON runners(last_heartbeat)
    WHERE status != 'OFFLINE';  -- for liveness detection
```

### Secrets

```sql
CREATE TABLE secrets (
    secret_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL REFERENCES organizations(org_id),
    repo_id         UUID REFERENCES repositories(repo_id),  -- NULL = org-level
    name            VARCHAR(100) NOT NULL,
    encrypted_value BYTEA NOT NULL,      -- KMS-wrapped AES-256 ciphertext
    kms_key_id      VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW(),
    last_accessed_at TIMESTAMPTZ,
    UNIQUE (org_id, repo_id, name)
);

CREATE INDEX idx_secrets_org ON secrets(org_id);
CREATE INDEX idx_secrets_repo ON secrets(repo_id) WHERE repo_id IS NOT NULL;

-- Audit trail (separate table, append-only)
CREATE TABLE secret_access_log (
    log_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    secret_id       UUID NOT NULL,
    org_id          UUID NOT NULL,
    job_id          UUID,
    runner_id       UUID,
    accessed_at     TIMESTAMPTZ DEFAULT NOW(),
    access_type     VARCHAR(20)  -- READ, CREATE, UPDATE, DELETE
);
CREATE INDEX idx_secret_log_secret ON secret_access_log(secret_id, accessed_at DESC);
```

### Artifacts

```sql
CREATE TABLE artifacts (
    artifact_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id          UUID NOT NULL REFERENCES pipeline_runs(run_id),
    job_id          UUID NOT NULL REFERENCES jobs(job_id),
    org_id          UUID NOT NULL,
    name            VARCHAR(255) NOT NULL,
    s3_bucket       VARCHAR(255) NOT NULL,
    s3_key          TEXT NOT NULL,
    size_bytes      BIGINT,
    content_type    VARCHAR(100),
    uploaded_at     TIMESTAMPTZ DEFAULT NOW(),
    expires_at      TIMESTAMPTZ,
    deleted         BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_artifacts_run ON artifacts(run_id);
CREATE INDEX idx_artifacts_expires ON artifacts(expires_at)
    WHERE deleted = FALSE;  -- for retention cleanup job
```

### Webhook Events (Idempotency)

```sql
CREATE TABLE webhook_events (
    delivery_id     VARCHAR(100) PRIMARY KEY,  -- X-GitHub-Delivery header
    provider        VARCHAR(20) NOT NULL,
    repo_id         UUID REFERENCES repositories(repo_id),
    event_type      VARCHAR(50),
    raw_payload     JSONB,
    received_at     TIMESTAMPTZ DEFAULT NOW(),
    processed       BOOLEAN DEFAULT FALSE,
    run_id          UUID REFERENCES pipeline_runs(run_id)
);

CREATE INDEX idx_webhook_received ON webhook_events(received_at DESC);
-- TTL: delete after 7 days (cron job)
```

---

## Partitioning Strategy

### Range Partitioning by Time (pipeline_runs, webhook_events)

```sql
CREATE TABLE pipeline_runs (
    ...
) PARTITION BY RANGE (created_at);

-- Create quarterly partitions
CREATE TABLE pipeline_runs_2024_q1 PARTITION OF pipeline_runs
    FOR VALUES FROM ('2024-01-01') TO ('2024-04-01');
```

**Benefits:**
- Drop old partition = instant cleanup (no row-by-row delete)
- Queries with `created_at > NOW() - INTERVAL '7 days'` hit only recent partition
- Parallel query across multiple workers on different partitions
- Vacuum runs on smaller, less fragmented tables

### Org-based Sharding (Future Phase)

At 10M runs/day, a single PostgreSQL cluster saturates:
- Shard key: `org_id`
- Runs for org A → shard 1, org B → shard 2
- Citus (PostgreSQL extension) for distributed execution
- Application-level routing via org-to-shard map

**Migration path:** Start single PostgreSQL with time partitioning. Introduce Citus sharding when single node approaches 70% write capacity (~5K writes/sec).

---

## Indexing Strategy

### Composite Indexes for Common Query Patterns

```sql
-- Most common: "list runs for a repo, ordered by created_at"
CREATE INDEX idx_runs_repo_created ON pipeline_runs(repo_id, created_at DESC);

-- Dashboard: "active runs per org"
CREATE INDEX idx_runs_org_active ON pipeline_runs(org_id, status)
    WHERE status IN ('PENDING', 'QUEUED', 'IN_PROGRESS');

-- Scheduler: "find queued jobs for dispatch"
CREATE INDEX idx_jobs_queued ON jobs(created_at, org_id)
    WHERE status = 'QUEUED';

-- Runner assignment: "jobs running on a specific runner"
CREATE INDEX idx_jobs_runner_status ON jobs(runner_id, status)
    WHERE status = 'RUNNING';

-- Secret lookup: "get secrets by org + name"
CREATE INDEX idx_secrets_lookup ON secrets(org_id, name);
```

**Partial indexes** (WHERE clause) are critical — they index only the small subset of rows matching the condition. `idx_runs_org_active` only indexes ~1% of runs (active ones) vs 100% if not partial.

---

## Read Replicas

| Use Case | Route to |
|---|---|
| Real-time status API (UI polls) | Read replica (eventual consistency OK) |
| Log streaming metadata | Read replica |
| Historical run list | Read replica |
| Run state transitions (Scheduler) | Primary |
| Secret fetch | Primary (must be consistent) |
| Artifact registration | Primary |

**Replication lag:** < 50ms typical. Acceptable for status display. Not acceptable for post-update reads (Scheduler must read its own writes from primary).

---

## Log Storage Design (Object Storage)

### Log File Format

```
S3 Key: logs/{orgId}/{runId}/{jobId}/log.gz

Content (gzipped NDJSON):
{"t":"2024-01-15T10:30:01.123Z","l":1,"s":0,"txt":"+ npm install"}
{"t":"2024-01-15T10:30:01.456Z","l":2,"s":0,"txt":"added 1023 packages in 45s"}
{"t":"2024-01-15T10:30:46.789Z","l":3,"s":1,"txt":"Running tests..."}

Fields: t=timestamp, l=lineNum, s=stepIndex, txt=text
```

**Line Index (for efficient seek):**
```
S3 Key: logs/{orgId}/{runId}/{jobId}/log.index

Format: [lineNum, byteOffset] pairs (sparse, every 1000 lines)
Allows seeking to line 5000 without reading entire log
```

### Redis Live Log Buffer

```
Key: log:stream:{jobId}
Type: List (RPUSH new lines, LTRIM to last 5000 lines)
TTL: 4 hours (cleared after job complete + grace period)

Structure:
  log:stream:{jobId} → ["line1\n", "line2\n", ...]
  log:meta:{jobId} → {currentLine: 245, flushedLine: 200}
```

**Log streaming flow:**
1. Runner POSTs log chunks to Log Service
2. Log Service: RPUSH to Redis, increment line counter
3. Async worker: every 10 seconds, LRANGE from last flushed line, gzip, append to S3
4. SSE consumer: `SUBSCRIBE log:pubsub:{jobId}` for real-time notification, then LRANGE Redis for content

---

## Data Retention

| Data Type | Hot Storage | Archive | Delete |
|---|---|---|---|
| Pipeline runs | 90 days PostgreSQL | S3 archive after 90 days | After 1 year |
| Job/step state | 90 days PostgreSQL | Deleted with parent run | |
| Logs (S3) | 30 days S3 Standard | S3 Infrequent Access (30-90 days) | 90 days |
| Artifacts | 30 days (configurable) | Not archived | After expiry |
| Secrets | Until deleted | Never archived | Immediate on delete |
| Webhook events | 7 days PostgreSQL | Deleted | After 7 days |
| Secret access audit | 1 year PostgreSQL | S3 after 1 year | Never (compliance) |

**Automated cleanup:**
- Daily cron: DELETE from pipeline_runs WHERE created_at < NOW() - INTERVAL '90 days'
- S3 lifecycle rules: transition to IA at 30 days, delete at 90 days
- Artifact expiry: cleanup job queries `artifacts WHERE expires_at < NOW()`, deletes S3 object, marks deleted

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| PostgreSQL time partitioning | Retention management; query performance on recent data | Partition management ops overhead |
| Redis for live log buffer | Sub-ms append/read for streaming | Data loss on Redis failure (acceptable — S3 is source of truth) |
| Encrypted secrets in same DB | Simpler ops than separate secrets DB | Encryption key management required; KMS dependency |
| Denormalized jobs_total/failed on run | Fast status aggregation query | Must update atomically when job status changes |
| JSONB for matrix_values | Flexible parameter storage | Less structured than dedicated columns; no direct indexing on matrix params |

---

## Interview Discussion Points

- **How do you handle 10M runs/day with a single PostgreSQL instance?** Horizontal partitioning by time + vertical scaling. At 10M/day × 10 rows/run (status updates) = 100M writes/day → ~1200 writes/sec. PG handles 5K writes/sec comfortably. Partitioning keeps individual table sizes manageable
- **Why store log lines in Redis before S3?** Real-time streaming requires sub-millisecond append + read. S3 PUT latency is ~100ms — too slow for per-line streaming. Redis buffer gives sub-ms append; S3 flush is async in background
- **How do you search logs?** For simple text search in recent runs: PostgreSQL `LIKE` on Redis-buffered logs. For full-text search in historical logs: index to Elasticsearch from S3 (on-demand or background). Most CI/CD tools provide only basic text search, not full FTS
- **What happens to an artifact after the run expires?** Artifact has its own `expires_at` timestamp (can be independent of run retention). Cleanup job checks `expires_at`, deletes S3 object, marks `deleted=TRUE` in DB. Run record may still exist even after artifacts are deleted
- **Why partial indexes on active runs?** Only ~0.1% of runs are active at any time. Full index on `status` would be 100% of all 100M+ runs. Partial index is 100K rows — 1000x smaller, faster lookups, less maintenance overhead
