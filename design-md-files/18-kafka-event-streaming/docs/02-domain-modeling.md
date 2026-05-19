# 02 — Domain Modeling: Kafka-like Event Streaming System

---

## Objective

Define the core domain entities, value objects, aggregates, and relationships that form the conceptual model of a distributed event streaming platform. Establish vocabulary that survives implementation language changes.

---

## Core Domain Concepts

### Topic
The logical channel through which messages flow. A topic is the primary unit of organization — producers write to topics, consumers read from topics.

**Properties:**
- `topicId`: globally unique UUID (immutable, survives renames)
- `name`: human-readable string (unique within cluster)
- `partitionCount`: number of partitions (fixed at creation; increasing is possible but disruptive)
- `replicationFactor`: number of replicas per partition
- `retentionMs`: time-based retention limit
- `retentionBytes`: size-based retention limit per partition
- `cleanupPolicy`: `delete` (drop old segments) or `compact` (retain latest per key)
- `minInsyncReplicas`: minimum ISR size for write acceptance
- `config`: map of optional overrides (max message size, compression, etc.)

**Invariants:**
- `replicationFactor ≤ clusterBrokerCount`
- `partitionCount ≥ 1`
- `minInsyncReplicas ≤ replicationFactor`

---

### Partition
The unit of parallelism and ordering. Each topic has N partitions; each partition is an independent ordered log.

**Properties:**
- `partitionId`: integer 0..N-1 within topic
- `topicId`: parent topic
- `leaderId`: broker currently serving as leader for this partition
- `isrList`: list of broker IDs that are in-sync with the leader
- `logStartOffset`: earliest available offset (moves forward as segments are deleted)
- `logEndOffset`: next offset to be written (high watermark visible to consumers)
- `highWatermark`: max offset replicated to all ISR members (safe to expose to consumers)

**Key Distinction — LEO vs HWM:**
- **LEO (Log End Offset)**: latest written offset on the leader
- **HWM (High Watermark)**: latest offset replicated to all ISR members
- Consumers can only read up to HWM — prevents reading uncommitted (not yet replicated) data

---

### Message (Record)
The atomic unit of data in the system. Immutable once written.

**Properties:**
- `offset`: position in partition log (monotonically increasing, assigned by leader)
- `timestamp`: producer-assigned or broker-assigned (configurable)
- `key`: optional bytes — determines partition assignment and used for log compaction
- `value`: payload bytes (opaque to broker)
- `headers`: list of key-value byte pairs for metadata (e.g., correlation ID, schema version)
- `partitionLeaderEpoch`: epoch of the leader that wrote this record (used for fencing)

**Batching:**
Messages are grouped into `RecordBatch` units for network efficiency:
- `baseOffset`, `lastOffset`: range of offsets in batch
- `magic`: wire protocol version
- `crc`: integrity check
- `attributes`: compression type, timestamp type, transactional flag
- `producerId`, `producerEpoch`, `sequence`: for idempotent/transactional producers

---

### Broker
A server node that stores partition logs and serves produce/fetch requests.

**Properties:**
- `brokerId`: integer, unique in cluster
- `host`, `port`: network address
- `rackId`: optional — used for rack-aware replica placement
- `epoch`: incremented on each broker restart (used for fencing stale requests)
- `partitionLeaderships`: set of partitions this broker leads

**Lifecycle:** `STARTING → REGISTERED → ACTIVE → FENCED (on failure) → REMOVED`

---

### Producer
Client entity that publishes messages.

**Properties:**
- `clientId`: human-readable name
- `producerId` (PID): assigned by broker for idempotence/transactions
- `producerEpoch`: incremented on `initTransactions` call — fences zombie producers
- `transactionalId`: optional — enables exactly-once semantics across sessions

**Idempotent Producer Model:**
Each message gets a monotonic `sequence` number per `(producerId, partition)`. Broker deduplicates re-sends within a sequence window — eliminates duplicate writes from producer retries.

---

### Consumer Group
A set of consumer instances sharing a topic subscription. The group guarantees each partition assigned to exactly one consumer at any time.

**Properties:**
- `groupId`: unique string identifier
- `groupEpoch`: incremented on membership change
- `state`: `Empty | PreparingRebalance | CompletingRebalance | Stable | Dead`
- `members`: list of active consumer instances
- `assignments`: map of `memberId → [partitions]`
- `coordinator`: broker elected to manage this group (via consistent hash of groupId)

**Group Coordinator Protocol:**
1. Consumer sends `JoinGroup` → coordinator waits for all members (timeout)
2. Coordinator selects group leader (first joiner) to run partition assignment strategy
3. Group leader sends `SyncGroup` with assignment plan
4. Coordinator distributes assignments to all members

---

### Offset
A consumer's read position in a partition. Managed by consumers; persisted in `__consumer_offsets`.

**Properties:**
- `groupId`, `topicId`, `partition`: composite key
- `committedOffset`: last processed offset + 1 (next to fetch)
- `metadata`: optional string (consumer-defined checkpoint info)
- `commitTimestamp`

**Offset Storage:**
`__consumer_offsets` is itself a compacted Kafka topic with 50 partitions. Offset commits are just writes to this topic — consistent, durable, and readable. No external DB needed.

---

### Log Segment
The physical storage unit within a partition. Each partition's log is divided into rolling segment files.

**Properties:**
- `baseOffset`: first offset in the segment
- `largestOffset`: last offset in the segment
- `logFile`: `.log` binary file (record batches)
- `offsetIndex`: `.index` sparse index (offset → file position)
- `timeIndex`: `.timeindex` sparse index (timestamp → offset)
- `txnIndex`: `.txnindex` aborted transaction index
- `state`: `Active | Closed | Compacted | Deleted`

**Segment Rolling Triggers:**
- Size exceeds `segment.bytes` (default 1 GB)
- Time exceeds `segment.ms` (default 7 days)
- Manual `flush` or leader change

---

## Entity Relationship Diagram

```
┌───────────┐        ┌───────────────┐        ┌──────────────┐
│  Topic    │1──────N│  Partition    │1──────N│  LogSegment  │
│           │        │               │        │              │
│ topicId   │        │ partitionId   │        │ baseOffset   │
│ name      │        │ leaderId      │        │ logFile      │
│ partCnt   │        │ isrList       │        │ offsetIndex  │
│ retentMs  │        │ hwm           │        │ sizeBytes    │
└───────────┘        └───────┬───────┘        └──────────────┘
                             │N
                             │ belongs to
                             │
┌───────────┐        ┌───────▼───────┐
│  Broker   │1──────N│  Partition    │ (as leader or replica)
│           │        │  Leadership   │
│ brokerId  │        └───────────────┘
│ host:port │
│ epoch     │
└───────────┘

┌─────────────────┐        ┌───────────────┐
│  ConsumerGroup  │1──────N│  Offset       │
│                 │        │               │
│ groupId         │        │ committedOff  │
│ state           │        │ partition     │
│ coordinator     │        │ metadata      │
└─────────────────┘        └───────────────┘

┌───────────┐
│  Message  │
│           │
│ offset    │
│ key       │
│ value     │
│ headers   │
│ timestamp │
└───────────┘
```

---

## Value Objects

| Object | Description |
|---|---|
| `TopicPartition` | Immutable (topicId, partitionId) pair — primary routing key |
| `OffsetAndMetadata` | (offset, metadata) committed by consumer |
| `RecordBatch` | Immutable group of records with shared metadata |
| `ProducerIdAndEpoch` | (producerId, epoch) for fencing zombie producers |
| `LeaderAndIsr` | Current leadership state of a partition (leaderId, isrList, leaderEpoch) |
| `ClusterMetadata` | Snapshot of all topic/partition/broker state (cached by clients) |

---

## Domain Events

| Event | Trigger | Consumers |
|---|---|---|
| `PartitionLeaderChanged` | Broker failure or reassignment | All brokers (update routing), all clients (refresh metadata) |
| `BrokerRegistered` | New broker joins cluster | Controller (assign replicas) |
| `BrokerFenced` | Broker unresponsive | Controller (trigger leader election) |
| `ConsumerGroupRebalanceTriggered` | Member join/leave, topic change | Group coordinator |
| `TopicCreated` | Admin API call | Controller (assign partitions to brokers) |
| `SegmentRolled` | Segment size/time limit hit | Log cleaner (check retention) |
| `OffsetRetentionExpired` | Group inactive > retention period | Coordinator (clean up offsets) |

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| Partition as ordering unit | Enables parallelism while preserving ordering per stream | No global ordering |
| HWM guards consumer reads | Prevents reading uncommitted data | Slightly lower consumer visible offset than LEO |
| Offset as consumer state | Consumer autonomy, replay support | Consumers must handle offset management |
| RecordBatch grouping | Amortizes per-message overhead | Latency for small-batch producers |

---

## Interview Discussion Points

- **Why is partitionId an integer, not a hash?** Routing is done by producer client (hash(key) % partitions). Integer ID is stable and simple to store/index
- **What is leader epoch?** Monotonic counter incremented on each leader change. Used to reject writes/fetches from brokers with stale leadership — prevents split-brain data corruption
- **Why is __consumer_offsets itself a Kafka topic?** Elegant dog-fooding — leverages same durability, replication, and compaction mechanisms. Eliminates external dependency for offset storage
- **What happens to offsets if group is inactive?** After `offsets.retention.minutes` (default 7 days), offsets are deleted. Consumer resumes from `auto.offset.reset` policy (earliest/latest)
- **What is the producerEpoch for?** Prevents zombie producers (e.g., GC-paused producer that reconnects) from writing stale data. New epoch fences old sessions
