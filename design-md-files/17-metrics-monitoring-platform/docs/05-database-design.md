# 05 — Database Design

## Objective

Design the complete multi-store persistence layer for the Metrics & Monitoring Platform. Each signal type (metrics, logs, traces) has radically different access patterns, cardinality, and retention requirements. This file covers the internal storage architecture of a Prometheus-compatible TSDB, Elasticsearch for logs, Cassandra/ClickHouse for traces, and PostgreSQL for all platform metadata. It addresses time partitioning, retention, tiered storage, compaction, cardinality explosion, and indexing in depth.

---

## 1. Storage Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                     Signal Type → Store Mapping                  │
├──────────────────┬─────────────────────────────────────────────-┤
│ Signal           │ Primary Store           │ Reason              │
├──────────────────┼─────────────────────────┼─────────────────────┤
│ Metrics          │ Prometheus TSDB (local) │ Column-oriented,     │
│                  │ Thanos/Mimir (scale-out)│ float64+timestamp   │
├──────────────────┼─────────────────────────┼─────────────────────┤
│ Logs             │ Elasticsearch / OpenSrch│ Full-text inverted   │
│                  │ (or Loki for label-based)│ index, structured  │
├──────────────────┼─────────────────────────┼─────────────────────┤
│ Traces           │ ClickHouse (preferred)  │ Column store, fast  │
│                  │ Cassandra (alternative) │ aggregations, cheap │
├──────────────────┼─────────────────────────┼─────────────────────┤
│ Metadata         │ PostgreSQL              │ ACID, relational,   │
│ (rules, dash,    │                         │ complex queries     │
│ users, sources)  │                         │                     │
└──────────────────┴─────────────────────────┴─────────────────────┘
```

---

## 2. Prometheus TSDB — Internal Architecture

Prometheus TSDB is the most critical and least understood component. Understanding its internals separates staff-level candidates from mid-level ones.

### 2a. Core Data Model

A **time series** is uniquely identified by its **label set**:

```
http_requests_total{method="GET", path="/api/v1/users", status="200", instance="app-01:8080", job="api-server"}
```

The combination of metric name + all label key-value pairs forms the series identity. Each unique combination is a separate series. A **sample** is a `(timestamp_ms int64, value float64)` pair stored within that series.

### 2b. TSDB Storage Layout

```
/data/
├── 01ABCDEFG.../          ← immutable block (2-hour chunk window, compacted)
│   ├── chunks/
│   │   ├── 000001         ← chunk segment file (max 512 MB)
│   │   └── 000002
│   ├── index              ← postings list + label index
│   └── meta.json          ← block metadata (min/max time, stats)
├── 01HIJKLMN.../          ← another immutable block
│   └── ...
├── wal/                   ← Write-Ahead Log (active writes)
│   ├── 00000001           ← WAL segment (128 MB max)
│   ├── 00000002
│   └── checkpoint.0000001 ← WAL checkpoint (series metadata snapshot)
└── chunks_head/           ← in-memory head block overflow to disk
    └── 000001
```

### 2c. Write Path

1. **WAL (Write-Ahead Log):**
   - Every incoming sample is first written to the WAL sequentially
   - WAL uses append-only writes — extremely I/O efficient (no random writes)
   - WAL records: `Series` (new label set registration), `Sample` (timestamp + value), `Tombstone` (deletion marker), `Exemplar`
   - WAL segments are 128 MB each; old segments are truncated after checkpointing
   - WAL provides crash recovery: on startup, TSDB replays WAL to reconstruct the in-memory head block

2. **Head Block (In-Memory):**
   - The head block holds the last 2 hours of data in memory (configurable)
   - Chunks within the head are compressed XOR chunks (see below)
   - Series are indexed in a hash map keyed on series fingerprint (hash of sorted label set)
   - The head block is mutable — new samples are appended to open chunks

3. **Chunk Encoding — XOR Compression:**
   - Timestamps: delta-of-delta encoding. First timestamp stored as-is. Second stores delta. Subsequent store delta-of-delta (change in the rate of change). Most timestamps are at regular intervals (e.g., every 15s), so delta-of-delta is often 0 → encoded in 1 bit.
   - Values: XOR encoding (Gorilla compression from Facebook's paper). Adjacent float64 values in a time series are highly correlated. XOR-ing consecutive values produces many leading/trailing zeros → efficiently encoded with variable-length codes.
   - Result: typical compression ratio of 1.37 bytes per sample (Prometheus) vs 16 bytes for raw `(int64, float64)` — ~11x compression.

4. **Block Persistence:**
   - Every 2 hours, the head block is flushed to an immutable disk block
   - Immutable blocks are Parquet-like: written once, read many times
   - Block files contain raw chunk data organized by series

### 2d. Compaction Strategy

Raw 2-hour blocks are inefficient for long-range queries (a 30-day query would open 360 blocks). Compaction merges and re-encodes blocks into larger time ranges:

```
Level 0: 2h blocks (raw, from head flush)
Level 1: 6h blocks (3x 2h merged)
Level 2: 18h blocks (3x 6h merged)
Level 3: 24h blocks (retention-aligned)
```

**Compaction Benefits:**
- Fewer open file descriptors for range queries
- Better chunk alignment → faster scans
- Tombstone resolution: deleted series are physically removed during compaction
- Overlapping blocks from crash recovery are deduplicated

**Compaction Algorithm:**
- Iterator-based merge of sorted series
- Re-encodes chunks with fresh XOR compression (can improve compression on cold data)
- Applies tombstones (logical deletes → physical removal)
- Produces new block with updated index

**When Compaction Becomes a Problem:**
- Large cardinality (millions of series): compaction is CPU and I/O intensive
- Write-heavy workloads: compaction competes with ingest for I/O bandwidth
- Compaction must be throttled to avoid I/O saturation — Prometheus has a compaction concurrency limit

### 2e. Index Structure

Each block has a binary index file containing:

1. **Symbol Table:** Deduplicated string pool of all label names and values. A label `job="prometheus"` stores "job" and "prometheus" as integer IDs referencing this pool.

2. **Series Index:** Each series entry contains:
   - Sorted label pairs (encoded as symbol IDs)
   - Chunk references: list of `(min_time, max_time, file_offset)` tuples pointing into chunk segment files

3. **Postings Lists:** For each `(label_name, label_value)` pair, a sorted list of series IDs that have that label. This is the inverted index that answers "give me all series where `job="api-server"`".
   - Implemented as delta-encoded sorted integer arrays
   - Intersection of two postings lists = AND operation (e.g., `job="api-server" AND method="GET"`)
   - Union = OR operation

4. **Label Name Index:** Map from label name → all posting lists for that name's values

**Index Size Concern:** With 10 million active series, the index can grow to 10+ GB. Keeping the index in memory (mmap) is essential for query performance. This is why Prometheus has a large memory footprint under high cardinality.

### 2f. Tombstones and Deletion

Prometheus does not delete individual samples immediately. Instead:
1. A delete request creates a `tombstone` entry: `(series_id, min_time, max_time)`
2. Tombstones are stored in a `tombstones` file per block
3. During reads, tombstoned ranges are skipped
4. During compaction, tombstoned ranges are physically removed
5. Full series deletion requires tombstones covering the entire series time range + a compaction cycle

This design choice (lazy deletion) optimizes for write throughput at the cost of immediate space reclamation.

### 2g. WAL Checkpointing

The WAL checkpoint is a snapshot of all active series metadata (label sets) at a point in time. Its purpose:
- Bounds WAL replay time on restart (only replay from the checkpoint forward, not from the beginning of time)
- Stores the series reference → label set mapping that the WAL samples reference
- Checkpoint is written to `checkpoint.XXXXXXXX/` directory atomically

Without checkpointing, a crash followed by WAL replay of millions of series would take minutes. With checkpointing, replay is bounded to the last checkpoint + new WAL segments.

---

## 3. Scale-Out TSDB: Thanos / Cortex / Mimir

### Problem with Single-Node Prometheus

- Single-node Prometheus stores all data locally — no horizontal scaling
- No global query view across multiple Prometheus instances
- Limited retention (typically 15 days due to disk constraints)
- No high availability (single point of failure)

### Thanos Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Thanos Components                     │
├────────────────┬────────────────────────────────────────-┤
│ Sidecar        │ Runs alongside each Prometheus instance  │
│                │ Uploads completed blocks to object store │
│                │ Serves gRPC Store API for recent data    │
├────────────────┼──────────────────────────────────────────┤
│ Store Gateway  │ Serves historical data from object store │
│                │ Caches index in local SSD (index cache)  │
├────────────────┼──────────────────────────────────────────┤
│ Querier        │ Fan-out query across all Store endpoints  │
│                │ Deduplicates replicated series           │
├────────────────┼──────────────────────────────────────────┤
│ Compactor      │ Global compaction on object store        │
│                │ Cross-block deduplication                │
│                │ Applies retention/downsampling           │
├────────────────┼──────────────────────────────────────────┤
│ Ruler          │ Remote alerting rule evaluation          │
├────────────────┼──────────────────────────────────────────┤
│ Query Frontend │ Request splitting, caching, sharding     │
└────────────────┴──────────────────────────────────────────┘
```

**Object Storage:** All historical blocks are stored in S3/GCS/Azure Blob. Storage cost is orders of magnitude lower than block storage. Retention becomes a cost decision, not a disk constraint decision.

**Deduplication:** When two Prometheus instances scrape the same targets (HA pair), they produce duplicate series. The Thanos Querier deduplicates by merging series from replicas that have the same label sets (modulo a `replica` label) and selecting the best sample at each timestamp.

### Mimir (Grafana Labs' Cortex successor)

Mimir extends the Thanos model for multi-tenant SaaS use cases:
- Native multi-tenancy (all stores are tenant-partitioned)
- Ingesters: accept RemoteWrite, buffer in memory, flush to object store
- Distributors: hash-ring-based routing of writes to ingesters
- Ruler: per-tenant alert rule evaluation
- Querier: fan-out across ingesters (recent data) + store-gateways (historical data)
- Compactor: per-tenant compaction in object store

---

## 4. Elasticsearch for Logs

### 4a. Index Design

**Index per time window:** A separate Elasticsearch index is created per time window to enable efficient retention management and ILM (Index Lifecycle Management).

```
logs-app-2026.05.19      ← hot index (today)
logs-app-2026.05.18      ← hot index (yesterday, still writable)
logs-app-2026.05.17      ← warm index (read-only)
...
logs-app-2026.04.19      ← cold index (read-only, compressed)
```

Index naming convention: `{signal-type}-{tenant}-{YYYY.MM.DD}` (or `{YYYY.MM.WW}` for weekly indices, `{YYYY.MM}` for monthly).

### 4b. Index Lifecycle Management (ILM)

ILM automates index transitions based on age, size, or document count:

```
Phase    │ Trigger                    │ Actions
─────────┼────────────────────────────┼────────────────────────────────
Hot      │ Default (new index)        │ Accept writes, 1 replica, fast SSD
Warm     │ Age > 2 days OR size > 50GB│ Move to warm nodes (HDD), force-merge to 1 segment, read-only
Cold     │ Age > 30 days              │ Move to cold nodes (object store), snapshot, read-only
Frozen   │ Age > 90 days              │ Fully on object store, search requires remount
Delete   │ Age > 365 days (or custom) │ Delete index
```

**Force Merge in Warm Phase:**
Each Elasticsearch segment has overhead (file handles, memory-mapped files). Force-merging to 1 segment per shard in the warm phase significantly reduces overhead and improves query performance on cold data.

### 4c. Mappings Design

Elasticsearch mapping defines how log fields are indexed. Poor mapping choices create performance problems at scale.

**Critical Mapping Decisions:**

| Field | Type | Indexing | Rationale |
|-------|------|----------|-----------|
| `@timestamp` | date (nanosecond precision) | Yes | Range queries, ILM |
| `body` / `message` | text | Analyzed (standard tokenizer) | Full-text search |
| `severity` | keyword | Yes | Exact match, aggregations |
| `service.name` | keyword | Yes | Filter, cardinality |
| `trace_id` | keyword | Yes | Exact match lookup |
| `span_id` | keyword | Yes | Exact match lookup |
| `host.name` | keyword | Yes | Filter |
| `attributes.*` | flattened or keyword | Dynamic | See below |
| `resource.*` | keyword | Yes | Filter |
| `body_length` | integer | No (stored only) | Metrics, not searched |

**Dynamic Mapping Problem:**

If arbitrary JSON objects are allowed as log attributes, Elasticsearch will dynamically create new fields for every unique key. With enough unique attribute keys (from different services), the **mapping explosion** problem occurs:
- Elasticsearch limits total field count per index (default: 1000)
- Exceeding this causes indexing failures
- Mitigation: use `flattened` field type for `attributes` — the entire object is stored as a single field; you can search for values but not aggregate by arbitrary keys without explicit mapping

**Index Sharding:**

Shard sizing is critical. Elasticsearch performance degrades with too-many-small-shards (heap overhead per shard) and too-few-large-shards (query parallelism limited).

Rule of thumb: 10-50 GB per shard. A daily index expecting 500 GB of logs should have 10-50 primary shards.

For a platform expecting 10 TB/day: `10 TB / 30 GB per shard = ~333 shards/day`. This is too many. Solution: weekly or monthly indices with larger shards, plus data streams for automatic rollover.

**Elasticsearch Data Streams:**
- A data stream is an abstraction over multiple time-based backing indices
- Write operations go to the "write index" (latest backing index)
- Rollover triggered by age, size, or document count
- ILM policies apply automatically to backing indices
- Query against the data stream name searches all backing indices transparently

### 4d. Sharding Strategy for Logs

```
┌─────────────────────────────────────────────────┐
│  Index: logs-prod-2026.05.19                    │
│  Primary Shards: 20                             │
│  Replica Shards: 1 (per primary)                │
│                                                 │
│  Shard routing: hash(service.name + timestamp)  │
│  → co-locates logs from same service per shard  │
│  → enables per-service parallel queries         │
└─────────────────────────────────────────────────┘
```

Custom routing by `service.name` improves query performance for service-filtered queries (most common access pattern) but risks hot shards if one service produces disproportionate log volume.

---

## 5. Cassandra vs ClickHouse for Traces

### Cassandra Approach (Jaeger Default)

Jaeger historically used Cassandra for trace storage. The data model:

**Table: `traces`**
- Partition key: `trace_id` (UUID)
- Clustering key: `span_id`
- Columns: `operation_name`, `flags`, `start_time`, `duration`, `tags` (map), `logs` (list), `refs` (list)

**Table: `service_names`**
- Partition key: `service_name`
- Simple lookup table

**Table: `operation_names`**
- Partition key: `service_name`
- Clustering key: `operation_name`

**Table: `service_operation_index`**
- Partition key: `service_name + operation_name + date`
- Clustering key: `start_time DESC, trace_id`
- Enables "find traces for service X, operation Y, in time range T" queries

**Cassandra Pros:** Mature, proven at scale, wide adoption in Jaeger deployments, good write throughput.

**Cassandra Cons:**
- Aggregation queries (P99 latency by service, error rate by operation) are very slow — Cassandra is not a good OLAP engine
- Compaction strategies require careful tuning for time-series-like trace data
- Limited secondary indexing — the index tables must be maintained manually (denormalized)
- Schema evolution is difficult

### ClickHouse Approach (Grafana Tempo, SigNoz)

ClickHouse is a columnar OLAP database, increasingly preferred for traces:

**Table: `otel_traces`**
- Engine: `MergeTree()` with `ORDER BY (ServiceName, Timestamp)`
- Partition: `toDate(Timestamp)` (daily partitions)
- Columns: TraceId, SpanId, ParentSpanId, ServiceName, SpanName, SpanKind, StartTimeUnix, DurationNano, StatusCode, StatusMessage, Tags (Map), ResourceAttributes (Map), Events (Array of structs), Links (Array of structs)

**Why ClickHouse Wins for Traces:**

| Capability | Cassandra | ClickHouse |
|------------|-----------|------------|
| Write throughput | Excellent | Very Good |
| Point lookup by trace_id | Excellent | Good (needs secondary index) |
| Aggregations (P99, counts) | Poor | Excellent |
| Compression ratio | Good | Excellent (5-10x better) |
| Flexible querying (ad-hoc) | Poor | Excellent |
| Operational complexity | High (tuning-heavy) | Medium |
| Cost per TB stored | Medium | Low |
| Multi-column indexes | Limited | Rich (skip indexes) |

**ClickHouse Skip Indexes for Traces:**
- `INDEX idx_trace_id TraceId TYPE bloom_filter(0.01) GRANULARITY 4` — enables O(1) trace_id lookup even though TraceId is not in the sort key
- `INDEX idx_duration DurationNano TYPE minmax GRANULARITY 1` — skip data blocks not in duration range
- `INDEX idx_status StatusCode TYPE set(3) GRANULARITY 1` — skip blocks not containing ERROR status

**ClickHouse TTL:**
```sql
TTL toDate(Timestamp) + INTERVAL 30 DAY DELETE,
    toDate(Timestamp) + INTERVAL 7 DAY TO DISK 'warm_disk',
    toDate(Timestamp) + INTERVAL 1 DAY TO DISK 'hot_disk'
```
Native tiered storage with automatic movement based on age.

---

## 6. PostgreSQL — Metadata Store

### 6a. ER Diagram

```
┌─────────────────┐     ┌───────────────────┐
│ organizations   │────<│ users             │
│ id (PK)         │     │ id (PK)           │
│ name            │     │ org_id (FK)       │
│ slug            │     │ email             │
│ plan            │     │ role              │
│ created_at      │     │ hashed_password   │
└────────┬────────┘     │ created_at        │
         │              └───────────────────┘
         │
         ├────────────────────────────────────────┐
         │                                        │
┌────────▼────────┐     ┌───────────────────┐  ┌─▼──────────────────┐
│ data_sources    │     │ alert_rule_groups  │  │ dashboards         │
│ id (PK)         │     │ id (PK)           │  │ id (PK)            │
│ org_id (FK)     │     │ org_id (FK)       │  │ org_id (FK)        │
│ name            │     │ name              │  │ uid (unique)       │
│ type            │     │ namespace         │  │ title              │
│ url             │     │ interval_seconds  │  │ folder_id (FK)     │
│ auth_config     │     │ created_at        │  │ version            │
│ is_default      │     │ updated_at        │  │ data (jsonb)       │
│ created_at      │     └────────┬──────────┘  │ created_at         │
└─────────────────┘              │             │ updated_at         │
                                 │             └────────────────────┘
                        ┌────────▼──────────┐
                        │ alert_rules        │
                        │ id (PK)           │
                        │ group_id (FK)     │
                        │ name              │
                        │ type (alert|record│
                        │ expr              │
                        │ for_duration      │
                        │ labels (jsonb)    │
                        │ annotations(jsonb)│
                        └───────────────────┘

┌─────────────────────────────┐
│ alert_instances             │
│ id (PK)                     │
│ rule_id (FK)                │
│ org_id (FK)                 │
│ labels_hash (varchar, index)│
│ current_state (enum)        │
│ state_changed_at            │
│ last_eval_at                │
│ value (float8)              │
└─────────────────────────────┘

┌─────────────────────────────┐   ┌──────────────────────────────┐
│ notification_policies       │   │ silence_rules                │
│ id (PK)                     │   │ id (PK)                      │
│ org_id (FK)                 │   │ org_id (FK)                  │
│ route_config (jsonb)        │   │ matchers (jsonb)             │
│ created_at                  │   │ starts_at                    │
└─────────────────────────────┘   │ ends_at                      │
                                  │ created_by                   │
                                  └──────────────────────────────┘
```

### 6b. Schema Design Notes

**`dashboards.data` as JSONB:**
Dashboard panel configurations are complex, evolving JSON structures. Storing as JSONB in PostgreSQL avoids a proliferation of narrow tables while still supporting GIN index searches on panel content. JSONB also supports partial updates via PostgreSQL's `jsonb_set` function.

**`alert_rule_groups.interval_seconds`:** Evaluation interval is stored separately from the PromQL expression to allow interval changes without touching the expression — important for monitoring expression evaluation history.

**`alert_instances` table:** This tracks the current state of every unique alert instance (a rule can fire multiple times with different label combinations — e.g., one alert rule for `high_error_rate` might fire separately for each service). The `labels_hash` is a deterministic hash of the firing labels, enabling fast state lookups.

**Soft Deletes:**
- All user-facing objects (`dashboards`, `data_sources`, `alert_rule_groups`) have `deleted_at TIMESTAMP NULL`
- Queries filter `WHERE deleted_at IS NULL`
- Enables audit trail and accidental deletion recovery
- Periodic background job permanently purges rows where `deleted_at < NOW() - INTERVAL '30 days'`

### 6c. Indexing Strategy

```sql
-- Fast org-scoped listing (most common query pattern)
CREATE INDEX idx_dashboards_org_id ON dashboards(org_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_datasources_org_id ON data_sources(org_id);

-- Alert instance state lookups (hot path during rule evaluation)
CREATE INDEX idx_alert_instances_rule_id ON alert_instances(rule_id);
CREATE INDEX idx_alert_instances_labels ON alert_instances(org_id, labels_hash);

-- Dashboard search by title (partial match)
CREATE INDEX idx_dashboards_title ON dashboards USING gin(to_tsvector('english', title));

-- Dashboard UID for stable API references
CREATE UNIQUE INDEX idx_dashboards_uid ON dashboards(uid) WHERE deleted_at IS NULL;

-- Alert rule JSONB label queries
CREATE INDEX idx_alert_rules_labels ON alert_rules USING gin(labels);
```

### 6d. Partitioning for High-Volume Tables

`alert_instances` can be very large in high-cardinality alert environments (thousands of rules × many instances per rule × history). Partitioned by `org_id` range or hash for multi-tenant deployments:

```sql
-- Range partitioning by creation month for audit/history tables
CREATE TABLE dashboard_versions (
    id          BIGSERIAL,
    dashboard_id BIGINT,
    version      INTEGER,
    data         JSONB,
    created_at   TIMESTAMPTZ NOT NULL
) PARTITION BY RANGE (created_at);

CREATE TABLE dashboard_versions_2026_05 
  PARTITION OF dashboard_versions 
  FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
```

---

## 7. Time Partitioning and Retention

### Retention Policies by Signal Type

| Signal | Default Hot Retention | Warm Retention | Cold Retention | Total |
|--------|--------------------|----------------|----------------|-------|
| Metrics (raw) | 15 days (local TSDB) | N/A | Object store, unlimited | Configurable |
| Metrics (5m downsampled) | 90 days | N/A | Object store, 2 years | |
| Metrics (1h downsampled) | N/A | Object store, 5 years | | |
| Logs | 7 days (hot ES) | 30 days (warm ES) | 1 year (frozen) | |
| Traces | 7 days | 30 days (cold ClickHouse) | N/A | |
| Metadata (PG) | Indefinite | | | Soft-delete purge at 30d |

### Downsampling for Metrics

Long-range queries (30-day, 1-year) over raw 15-second samples would require scanning billions of samples. Thanos Compactor applies **downsampling** during compaction:
- **5-minute downsampling:** For each 5-minute window, stores min, max, sum, count of raw samples. Enables reconstruction of avg (sum/count) and approximate range (min/max).
- **1-hour downsampling:** Same aggregations over 1-hour windows.
- Downsampled blocks are separate blocks with their own resolution metadata.
- The Query Frontend automatically selects the appropriate resolution based on the requested step size.

**Downsampling tradeoff:** Lossy aggregation. A query asking "what was the exact value at 14:37:22 on 2025-11-01?" cannot be answered from 1-hour downsampled data — only "what was the average/min/max between 14:00 and 15:00?". Most dashboard use cases accept this.

---

## 8. Cardinality Explosion Problem

This is the single most important operational problem in time-series databases and a critical interview topic.

### What Is Cardinality?

Cardinality = the number of unique time series. Each unique label set combination is a series.

`http_requests_total{service="X", method="GET", path="/api/...", status="200", user_id="12345"}` — if `user_id` is a label, cardinality = (number of users) × (number of services) × (number of paths) × (number of methods) × (number of statuses).

With 1 million users, this single metric creates 1 million × other_labels series. This is the **cardinality explosion**.

### How Cardinality Breaks Things

| Component | Impact of High Cardinality |
|-----------|---------------------------|
| TSDB Index | Index grows beyond available RAM → mmap thrashing → query latency spike |
| Series index (in-memory) | Each series takes ~700 bytes of memory → 10M series = 7 GB RAM just for index |
| Compaction | More series = longer compaction time → compaction queue backs up |
| Ingestion | Series registration (first sample) requires hash map lookup and WAL write → CPU bound |
| Query engine | Postings list intersection for high-cardinality selectors scans more series |
| Remote Write agents | Agents must track active series → memory pressure on the sending side |

### Cardinality Explosion Detection

Prometheus has native cardinality APIs:

- `GET /api/v1/status/tsdb` → returns top-N metric names by series count, top-N label names by series count
- `GET /api/v1/status/cardinality` → identifies highest-cardinality label combinations

**Alert on Cardinality:**
```
ALERT HighCardinalityGrowth
  EXPR rate(prometheus_tsdb_head_series[10m]) > 1000
  FOR 5m
  LABELS { severity="warning" }
  ANNOTATIONS { summary="Series growing at >1000/min — investigate new labels" }
```

### Cardinality Mitigation Strategies

1. **Label Validation at Ingest:** Reject series where any label has cardinality > configurable threshold per metric name. Return a non-retryable 400 to the client.

2. **Relabeling / Drop Rules:** Use `metric_relabel_configs` to drop high-cardinality labels before storage:
   - Drop `user_id` labels entirely
   - Hash `path` labels to a limited set of buckets if they contain dynamic segments

3. **Tenant Quotas:** Per-tenant maximum active series limit. Exceeded tenants receive 429 for new series creation.

4. **Cardinality Limiter at Remote Write:** The ingest service tracks per-tenant series count. New series (fingerprints not yet seen) are checked against the quota before being written to Kafka.

5. **Metric Naming Standards Enforcement:** Organizational standards that prohibit certain label names (`user_id`, `request_id`, `session_id`) as time-series labels. These should be in log/trace context, not metric labels.

6. **Active Series Monitoring Dashboard:** Automated weekly report of top-N metrics by cardinality growth, sent to owning teams.

---

## 9. Data Tiering (Hot / Warm / Cold)

### TSDB Data Tiering

```
Hot Tier (SSD, local disk):
  - Last 15 days raw data
  - In-memory head block (last 2 hours)
  - Chunks_head on SSD
  Cost: high (SSD block storage, $0.10-0.20/GB/month)

Warm Tier (object storage — recent):
  - 15 days to 90 days
  - Stored as Thanos blocks in S3/GCS
  - Store Gateway serves queries with SSD index cache
  Cost: medium (S3 standard, $0.023/GB/month + retrieval costs)

Cold Tier (object storage — archive):
  - 90 days to retention limit
  - S3 Glacier / Nearline storage
  - Queries trigger index remount from object store (higher latency)
  Cost: very low ($0.004/GB/month)
```

### Elasticsearch Log Tiering

Elasticsearch supports native tiered storage via node roles:
- **Hot nodes:** High-spec SSDs, accept writes, serve hot index searches
- **Warm nodes:** Larger HDDs or dense SSDs, read-only, force-merged indices
- **Cold nodes:** Object store backend (via searchable snapshots), indices frozen until queried
- **Frozen nodes:** Fully on object store, partial cache in node memory, slowest queries

ILM policy migrates indices across tiers automatically.

---

## 10. Compaction — Elasticsearch Perspective

After an index moves to the warm phase, force-merging reduces segment count:

**Why Segments Accumulate:**
- Elasticsearch writes use an in-memory buffer flushed periodically to disk as segments
- Each flush creates a new segment (Lucene segment = immutable, sorted data file + index structures)
- A shard can accumulate hundreds of segments, each requiring file handles and heap memory
- Merges happen in the background but can lag behind high-ingest rates

**Force Merge in Warm Phase:**
- Once an index is read-only, all segments can be merged into 1 per shard without write conflicts
- Single-segment shard has maximum query performance and minimum overhead
- Force merge is CPU and I/O intensive — must be scheduled during low traffic

---

## 11. Interview Discussion Points

1. **Walk me through what happens when Prometheus receives a sample.** Expected answer covers: WAL write → head block series lookup/creation → chunk append → periodic block flush → compaction. Full depth is the XOR encoding, delta-of-delta timestamps, and the role of the WAL in crash recovery.

2. **Why does high cardinality kill Prometheus?** The index must fit in memory for performant query execution. Each series takes ~700 bytes of index overhead plus chunk metadata. At 10 million series, the index alone is ~7 GB. Beyond available RAM, the kernel must swap mmap pages, causing I/O-bound query latency. Compaction also becomes CPU-bound as it must merge millions of series.

3. **How does the XOR compression in TSDB work?** Timestamp delta-of-delta is 0 for regular scrape intervals → 1 bit. Float XOR produces leading/trailing zeros → compact variable-length code. Combined effect: ~1.37 bytes per sample vs 16 bytes raw — ~11x compression.

4. **What is the difference between Thanos and Cortex/Mimir?** Thanos is a sidecar model — Prometheus continues to own ingest and local TSDB; Thanos adds long-term storage and global query. Cortex/Mimir replace the Prometheus ingest layer entirely with a horizontally scalable distributed system (ingester ring, distributor, block manager). Cortex/Mimir is operationally more complex but scales to billions of active series.

5. **Why ClickHouse over Cassandra for traces?** Aggregation performance. The most valuable trace queries are "P99 latency for service X over the last hour" and "error rate by operation" — OLAP aggregations. Cassandra is optimized for row-level reads/writes, not OLAP. ClickHouse columnar storage + MergeTree engine + vectorized execution makes aggregations 100-1000x faster. Also, ClickHouse's native TTL and tiered storage simplify retention management.

6. **How do you handle the Elasticsearch mapping explosion problem?** Limit dynamic field creation by using `dynamic: "strict"` or `dynamic: "false"` on the attributes sub-object, and use the `flattened` field type. Flattened stores the entire object as a single Lucene field, allowing value searches but not field-level aggregations on arbitrary keys. For structured attributes, pre-define mappings for known high-value fields.

7. **Explain Elasticsearch ILM and why it matters for log retention.** Without ILM, log indices grow indefinitely. ILM automates the lifecycle: hot (write-enabled, SSD) → warm (read-only, force-merged, HDD) → cold (frozen, object store) → delete. This enables unbounded retention at declining cost tiers without manual intervention. The platform operator only defines the ILM policy; the rest is automated.

8. **How does Prometheus tombstone deletion work and when does space get reclaimed?** A delete request creates a tombstone range (not immediately removing data). The tombstone is applied at query time (deleted ranges are skipped) and at compaction time (samples in the range are physically removed). Space is reclaimed only after a compaction cycle that covers the tombstoned time range. This means a delete request for last week's data may not reclaim disk space for several hours until the compactor runs.
