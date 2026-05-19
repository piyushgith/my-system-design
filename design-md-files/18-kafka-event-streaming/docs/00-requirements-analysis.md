# 00 — Requirements Analysis: Kafka-like Event Streaming System

---

## Objective

Define functional and non-functional requirements, constraints, assumptions, and capacity estimates for a production-grade distributed event streaming platform capable of handling high-throughput, durable, ordered message delivery across multiple consumers — analogous to Apache Kafka.

---

## Functional Requirements

### Core (MVP)
- Producers publish messages to named **topics**
- Topics are divided into **partitions** for parallelism
- Consumers subscribe to topics and read messages sequentially per partition
- Messages are **durably persisted** to disk — not deleted on consumption
- Each message has a monotonically increasing **offset** per partition
- Consumers track their own read position (offset) — the broker does not auto-advance it
- Messages can be replayed by seeking to any offset

### Extended (V1)
- **Consumer groups**: multiple consumers sharing a topic, each partition assigned to exactly one consumer in a group
- **Replication**: each partition has a leader and N replicas for fault tolerance
- **Retention policies**: time-based (e.g., 7 days) and size-based (e.g., 1 TB per topic)
- **Topic configuration**: per-topic replication factor, partition count, retention
- **Producer acknowledgment modes**: fire-and-forget, leader ack, all-replicas ack
- **At-least-once delivery** semantics by default; **exactly-once** as opt-in
- **Schema registry** integration for message format enforcement (Avro/Protobuf/JSON Schema)

### Advanced (V2+)
- **Log compaction**: retain only the latest message per key (useful for change data capture)
- **Transactions**: atomic multi-partition writes (producer transactions)
- **Kafka Streams / stream processing integration** hooks
- **Tiered storage**: offload old segments to object storage (S3/GCS) for infinite retention at low cost
- **Quotas**: per-client producer/consumer bandwidth throttling
- **Multi-datacenter replication** (MirrorMaker-equivalent)
- **Admin API**: create/delete topics, reassign partitions, adjust configs

---

## Non-Functional Requirements

| Property | Target |
|---|---|
| Availability | 99.99% (< 52 min downtime/year) |
| Durability | No committed message loss (replicated before ack) |
| Write throughput | 1 million messages/second per cluster |
| Read throughput | 10 million messages/second (fan-out to consumers) |
| End-to-end latency (producer → consumer) | < 10ms p99 under normal load |
| Message ordering | Strict per partition; no global ordering guarantee |
| Scalability | Horizontal — add brokers and partitions independently |
| Message size | 1 byte to 10 MB per message; default max 1 MB |

---

## Assumptions

- Messages are opaque byte arrays — the broker does not parse payload
- Producers and consumers are trusted internal services (not public internet clients in V1)
- Clock skew between nodes is bounded (NTP synchronization assumed)
- Network partitions are rare but must be handled gracefully
- Disk I/O is the primary bottleneck — sequential writes assumed for performance
- Consumers are responsible for offset management (commit to broker or manage externally)
- Topics are pre-created by operators, not auto-created on first publish (configurable)
- The system runs on Linux with JVM or native runtime

---

## Constraints

- Partition count is fixed at topic creation (repartitioning is expensive and disruptive)
- Partition count determines max consumer parallelism per group
- A partition can only be consumed by **one consumer per group** at a time
- Replication factor ≤ broker count (cannot replicate to more brokers than exist)
- Log segments are immutable once written; compaction is a background process
- Strong ordering is guaranteed only within a partition, not across partitions

---

## Scale Estimation

### Traffic Assumptions

| Metric | Value |
|---|---|
| Topics | 10,000 |
| Partitions per topic (avg) | 12 |
| Total partitions | 120,000 |
| Producer write rate | 1M messages/sec |
| Avg message size | 1 KB |
| Consumer read multiplier | 10× (10 consumer groups per topic avg) |
| Consumer read rate | 10M messages/sec |
| Brokers in cluster | 30–100 |

### Back-of-the-Envelope Calculations

**Write throughput per broker**

- 1M msg/sec × 1 KB = **1 GB/sec** inbound
- With replication factor 3: each message written 3× → 3 GB/sec disk writes across cluster
- Per broker (30 nodes): ~100 MB/sec write — within NVMe SSD capability (500+ MB/sec seq write)

**Read throughput**

- 10M msg/sec × 1 KB = **10 GB/sec** outbound (served from page cache, not disk)
- Per broker: ~333 MB/sec — well within kernel page cache + network (10 GbE = 1.25 GB/sec)

**Storage**

- 1 GB/sec × 86400 sec/day = **86.4 TB/day** raw
- With replication factor 3: **259 TB/day**
- 7-day retention: ~1.8 PB total cluster storage
- Per broker (100 nodes): ~18 TB — fits on 4× 4TB NVMe SSDs per broker

**Segment file sizing**

- Default segment size: 1 GB — rolled every ~17 minutes at 1 GB/sec per broker
- Index file per segment: ~10 MB (offset + timestamp index)

---

## Read/Write Patterns

| Operation | Pattern | Frequency |
|---|---|---|
| Producer publish | Sequential append to partition log | Very High |
| Consumer fetch | Sequential read from offset | Very High |
| Offset commit | Periodic write to __consumer_offsets topic | High |
| Partition leader election | Triggered on broker failure | Rare |
| Log segment rolling | Background; time/size-based | Continuous |
| Log compaction | Background per-partition scan | Continuous (low priority) |
| Metadata fetch | Broker/partition leader lookup | On connection + rebalance |

**Key Insight**: Write path is pure sequential append — the single most cache-friendly and disk-efficient I/O pattern. Read path serves from OS page cache (zero-copy via `sendfile`). The hot path never involves random I/O.

---

## Latency Expectations

| Operation | Target P50 | Target P99 |
|---|---|---|
| Producer publish (acks=1) | < 1ms | < 5ms |
| Producer publish (acks=all) | < 5ms | < 10ms |
| Consumer fetch (cache hit) | < 2ms | < 8ms |
| Consumer fetch (disk fallback) | < 20ms | < 50ms |
| Consumer group rebalance | < 30sec | < 60sec |
| Partition leader failover | < 10sec | < 30sec |

---

## Availability Targets

| Component | Availability |
|---|---|
| Broker cluster (data plane) | 99.99% |
| ZooKeeper / KRaft controller | 99.99% |
| Schema registry | 99.9% |
| Admin API | 99.5% |

---

## Tradeoffs Acknowledged at Requirements Level

| Decision | Tradeoff |
|---|---|
| Pull-based consumers vs push | Pull = consumer controls pace (backpressure natural); push = lower latency but risk overwhelming slow consumers |
| acks=all vs acks=1 | acks=all = no data loss risk; acks=1 = lower latency, leader failure can lose uncommitted messages |
| Fixed partition count | Simplicity + ordering guarantee; repartitioning breaks key-based routing |
| No global ordering | Required for horizontal scale; clients must handle cross-partition ordering if needed |
| Immutable log | Simplifies replication and caching; compaction adds complexity for key-based use cases |
| Consumer manages offsets | Flexibility (replay, custom checkpointing); complexity (client must handle offset persistence) |

---

## Interview Discussion Points

- **Why pull vs push?** Kafka pull = consumer controls rate → natural backpressure. Push systems (RabbitMQ) push to consumers → need complex flow control when consumers are slow
- **What is the ordering guarantee?** Per-partition strict ordering. If global ordering needed, use 1 partition (sacrifices parallelism)
- **How does replication work without 2PC?** ISR (In-Sync Replicas) — leader tracks which replicas are caught up; only ISR members eligible for leader election
- **What happens during network partition?** ISR shrinks; if `min.insync.replicas` not met, writes reject (CP behavior). Can tune to AP by lowering acks
- **What limits throughput?** Sequential disk write bandwidth, network bandwidth, and ZooKeeper/controller throughput for metadata operations
