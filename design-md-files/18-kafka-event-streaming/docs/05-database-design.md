# 05 — Database Design: Kafka-like Event Streaming System

---

## Objective

Define the storage design for message logs, metadata, consumer offsets, and schema registry. Unlike traditional database-centric systems, the core storage layer is a custom log-structured file format — not a relational database. This file explains why and how.

---

## Storage Architecture Overview

| Data Type | Storage Layer | Rationale |
|---|---|---|
| Message data (logs) | Local disk (custom log segments) | Sequential write maximizes throughput |
| Cluster metadata | KRaft metadata log (Kafka topic) | Self-hosting; consistent via Raft |
| Consumer offsets | `__consumer_offsets` (Kafka topic) | Leverages same durability + compaction |
| Schema definitions | PostgreSQL (Schema Registry) | Relational queries, versioning, compatibility |
| Metrics/admin state | In-memory (cached from metadata log) | Low latency reads, rebuilt on restart |

---

## Log Segment File Design

The core storage primitive is the **log segment** — an immutable file containing a sequence of record batches.

### Directory Layout

```
/kafka-logs/
  user-events-0/           ← partition directory (topicName-partitionId)
    00000000000000000000.log       ← record data (binary)
    00000000000000000000.index     ← offset → file position index
    00000000000000000000.timeindex ← timestamp → offset index
    00000000000000000000.txnindex  ← aborted transaction index
    00000000000001234567.log       ← next segment (rolled after 1GB or 7 days)
    00000000000001234567.index
    leader-epoch-checkpoint        ← maps leaderEpoch → start offset
    partition.metadata             ← topicId, partitionId

  __consumer_offsets-12/   ← internal offset tracking partition
    ...
```

### .log File Structure (RecordBatch layout)

```
RecordBatch:
  [8 bytes]  baseOffset           ← offset of first record in batch
  [4 bytes]  batchLength          ← bytes of everything after this field
  [4 bytes]  partitionLeaderEpoch ← epoch when batch was written
  [1 byte ]  magic                ← format version (current: 2)
  [4 bytes]  crc32c               ← checksum of everything after
  [2 bytes]  attributes           ← compression, timestamp type, transactional, control
  [4 bytes]  lastOffsetDelta      ← lastOffset - baseOffset
  [8 bytes]  firstTimestamp
  [8 bytes]  maxTimestamp
  [8 bytes]  producerId           ← -1 if non-transactional
  [2 bytes]  producerEpoch
  [4 bytes]  baseSequence         ← for idempotent producers
  [4 bytes]  recordCount
  [Record...] records             ← varint-length-prefixed records
```

Each `Record` inside a batch:
```
  [varint] length
  [1 byte] attributes
  [varint] timestampDelta    ← relative to batch firstTimestamp
  [varint] offsetDelta       ← relative to batch baseOffset
  [varint] keyLength (-1 = null)
  [bytes ] key
  [varint] valueLength
  [bytes ] value
  [varint] headersCount
  [Header...] headers
```

**Why varint for per-record fields?** Most records in a batch share similar timestamps and close offsets — varint encoding compresses these to 1–2 bytes. Significant space savings at high volume.

---

### .index File (Offset Index)

Sparse index: not every offset is indexed, only every `index.interval.bytes` (default 4096 bytes).

```
Index Entry (8 bytes each):
  [4 bytes] relativeOffset   ← offset - baseOffset (fits in 4 bytes; max 2^32 per segment)
  [4 bytes] position         ← byte position in .log file
```

**Lookup algorithm:**
1. Binary search index for largest entry ≤ target offset
2. Seek to that file position in .log
3. Scan forward to find exact offset (max ~4 KB scan)

This gives O(log n) + O(1) amortized lookup — negligible for cached segments.

---

### .timeindex File (Timestamp Index)

Same structure as offset index but maps `timestamp → offset`. Used for time-based offset lookup (`offsetsForTimes` API). Also sparse — indexed every `index.interval.bytes`.

---

## Log Compaction Design

For topics with `cleanup.policy=compact`:

### How it works
1. Log cleaner (background thread) scans "dirty" (not yet compacted) segments
2. Builds in-memory hash map: `key → latest offset`
3. Rewrites dirty segments, discarding all but the latest record per key
4. Null-value records ("tombstones") are retained until after all consumers have consumed them, then deleted

### Compaction Guarantees
- Latest value for any key is always available (unless tombstoned)
- No ordering change: relative order of surviving records preserved
- Log start offset advances past deleted records — consumers must handle `OFFSET_OUT_OF_RANGE`

### Compaction Ratio
- `min.cleanable.dirty.ratio` (default 0.5): only compact when >50% of log is dirty
- Prevents constant background I/O churn

---

## Cluster Metadata Storage (KRaft)

KRaft replaces ZooKeeper with a Kafka topic (`__cluster_metadata`) stored on dedicated controller nodes.

### Metadata Records

All cluster state is stored as events in the metadata log:

| Record Type | Fields |
|---|---|
| `BrokerRegistrationRecord` | brokerId, host, port, rack, epoch |
| `TopicRecord` | topicId, name |
| `PartitionRecord` | topicId, partitionId, leader, isr, replicas, leaderEpoch |
| `PartitionChangeRecord` | partition change delta (leader/isr change) |
| `BrokerRegistrationChangeRecord` | broker state change |
| `RemoveTopicRecord` | topicId, deletion marker |
| `ConfigRecord` | resource type, resource name, key, value |
| `ProducerIdsRecord` | range of producerIds assigned to broker |

**Why event-sourced metadata?** Current cluster state = replay of all metadata events from snapshot. Snapshots reduce replay time on restart. Same Raft-backed durability as user data. Eliminates ZooKeeper dependency entirely.

### Metadata Replication
- Controller nodes form a Raft quorum (3 or 5 nodes)
- Leader processes all metadata writes
- Followers replicate and can serve metadata reads
- Brokers act as observers — they fetch from the metadata log but don't vote

---

## Consumer Offsets Storage (`__consumer_offsets`)

Internal compacted topic with 50 partitions (configurable). Consumer group's coordinator broker is the leader for `hash(groupId) % 50` partition.

### Offset Commit Record (key + value)

**Key:**
```
OffsetCommitKey:
  version: int16
  group: string
  topic: string
  partition: int32
```

**Value:**
```
OffsetCommitValue (v3):
  offset: int64
  leaderEpoch: int32
  metadata: string
  commitTimestamp: int64
  expireTimestamp: int64   ← -1 for no expiry
```

**Group Metadata Record:**
Coordinator also writes group state (members, protocol, generation ID) to `__consumer_offsets` for durability.

### Compaction Behavior
- Latest commit per `(group, topic, partition)` key is retained
- Stale group metadata cleaned up after `offsets.retention.minutes` (default 7 days)

---

## Schema Registry Storage (PostgreSQL)

### Tables

```sql
CREATE TABLE subjects (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(255) UNIQUE NOT NULL,
    compatibility VARCHAR(50) DEFAULT 'BACKWARD',
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    deleted     BOOLEAN DEFAULT FALSE
);

CREATE TABLE schema_versions (
    id              SERIAL PRIMARY KEY,
    subject_id      INTEGER REFERENCES subjects(id),
    version         INTEGER NOT NULL,
    schema_id       INTEGER NOT NULL,  -- global schema ID
    schema_type     VARCHAR(20) NOT NULL,  -- AVRO, PROTOBUF, JSON
    schema_json     TEXT NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    deleted         BOOLEAN DEFAULT FALSE,
    UNIQUE (subject_id, version)
);

CREATE TABLE schemas (
    id          SERIAL PRIMARY KEY,
    schema_hash VARCHAR(64) UNIQUE NOT NULL,  -- SHA256 of normalized schema
    schema_json TEXT NOT NULL,
    schema_type VARCHAR(20) NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_schema_hash ON schemas(schema_hash);
CREATE INDEX idx_schema_versions_subject ON schema_versions(subject_id, version);
```

**Why normalize schemas?** Same schema registered under multiple subjects gets one row in `schemas` table (dedup by hash). `schema_id` is globally unique — consumers can cache by ID regardless of subject.

### Schema Registry Read Pattern
- `GET /schemas/ids/{id}` is the hot read path — served from in-memory cache
- Cache: `HashMap<Integer, ParsedSchema>` loaded on startup
- Cache invalidation: none needed (schema IDs are immutable; schemas never modified after registration)

---

## Partitioning Strategy

### Message Log Partitioning
- Partition = independent ordered log = unit of parallelism
- **Key-based routing:** `hash(key) % numPartitions` — same key always routes to same partition, enabling stream joins and ordered processing per entity
- **Null key routing:** Round-robin across partitions or sticky partition (batch until `linger.ms` or `batch.size` exceeded, then rotate)

### Partition Count Guidelines

| Use Case | Recommended Partitions |
|---|---|
| Low-throughput topic | 3–6 |
| Medium-throughput | 12–24 |
| High-throughput (>100 MB/sec) | 48–96 |
| Max consumer parallelism = max partitions | Set for expected max consumers |

**Why not 1000 partitions per topic?**
- Each partition = open file handles on every broker
- Controller manages O(partitions) metadata — memory and sync cost
- Rebalance time grows with partition count
- Rule: start conservative, increase deliberately

---

## Indexing Strategy

| Index | Type | Purpose |
|---|---|---|
| Offset index per segment | Sparse array | Offset → file position lookup |
| Timestamp index per segment | Sparse array | Time-based offset lookup |
| Leader epoch checkpoint | Sequential file | Epoch → start offset mapping for truncation |
| Schema hash index (PG) | B-tree | Dedup schema registration |
| Subject+version (PG) | Composite B-tree | Version history lookup |

---

## Data Retention & Archival

### Active Retention
- **Time-based:** Delete segments where `maxTimestamp < now - retention.ms`
- **Size-based:** Delete oldest segments when partition log exceeds `retention.bytes`
- Whichever limit is hit first triggers deletion
- Deleted on leader, replicas follow

### Tiered Storage (Advanced)
1. Active segments on local NVMe (hot tier)
2. Closed segments offloaded to S3/GCS after `local.retention.ms`
3. Remote segments served via custom `RemoteStorageManager` plugin
4. Consumers transparently read from either local or remote storage
5. Enables "infinite" retention at object storage cost (~$23/TB/month vs ~$100/TB SSD)

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| Custom log format vs RocksDB | Sequential I/O 10x faster; page cache efficiency; zero-copy | Complex custom format to maintain |
| Sparse offset index | 10x smaller than dense; log scan for last few KB is fast | Slightly more latency for cold reads |
| PostgreSQL for schema registry | ACID for schema versioning, compatibility checks | Separate DB dependency; not needed for core broker |
| Compacted topic for offsets | Elegant reuse of broker storage; no external DB | Coordinator broker is hotspot for popular groups |
| Fixed segment size (1 GB) | Bounded index size; predictable rolling | Wasted space for sparse partitions |

---

## Interview Discussion Points

- **Why not use RocksDB like some messaging systems?** RocksDB is great for random access workloads; Kafka's workload is purely sequential. Sequential I/O on a log file with OS page cache outperforms RocksDB for this access pattern
- **How does a new replica catch up?** Follower fetches from leader starting at its current LEO. Leader replicates all segments. Consumer offset for that partition is unaffected — replica is read-only
- **What happens when a segment is deleted while a consumer is reading it?** Consumer gets `OFFSET_OUT_OF_RANGE`. Client handles via `auto.offset.reset` or manual recovery. This is expected behavior for slow consumers behind retention
- **How does log compaction handle concurrent writes?** Compaction runs on closed (immutable) segments. Active segment is never compacted. Cleaner reads closed segments, writes cleaned copy to temp file, atomically replaces original
- **What is the write-ahead-log for?** The log IS the WAL. There's no separate WAL — the partition log itself serves as both the data store and the write-ahead durability guarantee
