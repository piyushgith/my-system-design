# 14 — Interview Discussion Points: Kafka-like Event Streaming System

---

## Objective

Prepare for staff-level system design interviews by anticipating follow-up questions, common traps, scaling evolution questions, and the "what breaks first" analysis specific to distributed event streaming.

---

## Expected Interviewer Questions by Area

### Architecture Fundamentals

**Q: Why is Kafka a log and not a traditional message queue?**
> The log is the fundamental insight. Immutable append-only log enables: (1) O(1) appends regardless of consumer state — broker doesn't track who consumed what, (2) arbitrary replay by any consumer at any time, (3) multiple consumer groups reading the same data independently, (4) sequential I/O = maximum disk throughput, (5) OS page cache serves most reads — effectively RAM speed. Traditional MQ (RabbitMQ) pushes and deletes — you can't go back, you can't have two consumers get the same message, and random access patterns kill disk performance.

**Q: What is the ordering guarantee in Kafka?**
> Strict total order within a partition. No ordering guarantee across partitions. This is the fundamental tradeoff enabling horizontal scale. If you need global ordering, use 1 partition — you sacrifice parallelism for ordering. In practice: partition by entity ID (userId, orderId) — ordering is preserved for all events of the same entity. Cross-entity ordering (order A before order B from different users) is undefined in Kafka and must be handled at the application layer.

**Q: Why pull-based consumers instead of push?**
> Three reasons: (1) Natural backpressure — slow consumer doesn't get overwhelmed, it just fetches slower, (2) Consumer autonomy — consumer decides when to process, can pause/resume without coordination with broker, (3) Replay — consumer can seek to any offset and pull from there. Push-based systems (RabbitMQ) need complex credit/flow-control mechanisms; pull makes this free.

---

### Replication and Durability

**Q: Explain the ISR mechanism and when it can lead to data loss.**
> ISR (In-Sync Replicas) = set of replicas whose LEO is within `replica.lag.time.max.ms` of the leader. acks=all only waits for ISR members — not all replicas. Data loss scenario: leader acknowledges write (acks=all, min.isr=2), 2 ISR members confirm. Then both ISR members crash before leader can replicate to 3rd replica. Leader then crashes. 3rd replica (never in ISR) gets elected as leader via unclean election. It doesn't have the last acknowledged write → **data loss**. Prevention: `unclean.leader.election.enable=false` (never elect non-ISR as leader). At-risk window only exists with `unclean.leader.election.enable=true`.

**Q: What is the difference between LEO and high watermark?**
> LEO (Log End Offset) = latest offset written on a specific broker's log. High Watermark (HWM) = highest offset replicated to all ISR members. Consumers can only read up to HWM — never beyond. This prevents consumers from reading data that might be truncated if the leader fails (because non-HWM data might not be on all ISR members). After leader crash, new leader truncates its log to the last HWM. If consumers could read past HWM, they'd be reading data that might disappear — breaking the delivery guarantee.

**Q: How does exactly-once delivery work?**
> Two components: (1) Idempotent producer: each batch has (producerId, epoch, sequence). Broker deduplicates re-sent batches within a 5-batch window — handles producer retries. (2) Transactions: producer wraps multi-partition writes in a transaction. Transaction coordinator (elected broker) writes PREPARE/COMMIT to `__transaction_state` log. On commit, COMMIT markers written to all participating partitions. Consumers with `isolation.level=READ_COMMITTED` only see committed data. Even if coordinator crashes mid-commit, recovery reads `__transaction_state` and completes the commit — atomicity guaranteed.

---

### Scalability

**Q: What are the bottlenecks in Kafka?**
> In order of likelihood: (1) **Controller throughput** (ZooKeeper era) — ZooKeeper bottleneck at ~200K partitions. KRaft solves this. (2) **Disk sequential write bandwidth** — NVMe ~500 MB/sec per broker. Add brokers when approaching. (3) **JVM GC pauses** — GC pause > replica.lag.time.max.ms causes ISR shrinkage, acks=all blocks. Keep heap small (6 GB), use G1GC/ZGC. (4) **NIC bandwidth** — 10 GbE = 1.25 GB/sec. Consumer fan-out can saturate this. (5) **Partition count per broker** — each partition = open file handles. > 4000 partitions per broker causes memory/file handle exhaustion.

**Q: How do you handle a hot partition?**
> Diagnosis first: `kafka-consumer-groups.sh --describe` + Prometheus per-partition throughput shows which partition is overwhelmed. Solutions: (1) Key salting — append random suffix (0–N) to hot key, fan-out to N partitions, aggregate downstream. Problem: breaks ordering, requires merge step. (2) Composite key with subentity — `userId_entityType`. (3) Topic isolation — dedicated topic for hot producer with more partitions. (4) Producer-side throttling — rate limit the hot producer at source. The real fix is usually redesigning the key choice — if one key dominates, round-robin (null key) with a downstream join is often cleaner than key salting.

**Q: Why can't you have more consumers than partitions?**
> Each partition is assigned to exactly one consumer per group at any time (enforced by group coordinator). This is the ordering guarantee — multiple consumers reading the same partition would create ordering violations. Extra consumers are idle. The ceiling is hard. Design: set partition count = expected max consumer parallelism × 2 (buffer for growth). Increasing partition count later is possible but disrupts key-based routing.

---

### Failure and Recovery

**Q: What is unclean leader election and when would you enable it?**
> Unclean leader election = electing a replica that was NOT in ISR as the new leader. Risk: this replica may be significantly behind the old leader → data loss. When to enable: only if availability > durability. Example: analytics pipeline where losing a few messages is acceptable, but 30-second downtime (waiting for ISR member) is not. Default: false (prioritize durability). Never enable for financial, billing, or compliance data streams.

**Q: What happens during a network partition (split-brain)?**
> With acks=all and min.isr=2: isolated leader cannot form quorum → writes rejected. No split-brain. With acks=1: isolated leader accepts writes, acknowledges to producer. When partition heals, isolated leader truncates to HWM → data loss. Producer received false success. This is the fundamental acks=1 risk. Detection: producers getting `NetworkException` on retry after receiving success ack → impossible to know if message was committed or lost. Idempotent producer helps detect this — sequence gap alerts producer to re-send.

**Q: How does Kafka handle clock skew?**
> Timestamps in RecordBatch can be `CREATE_TIME` (producer-assigned) or `LOG_APPEND_TIME` (broker-assigned). Log retention by time uses max timestamp in segment. If producer clocks are skewed forward, retention calculates incorrectly. Production recommendation: `log.message.timestamp.type=LOG_APPEND_TIME` — broker assigns timestamp, controlled by NTP on brokers. Create time is useful for event semantics but unreliable for retention.

---

### Design Decisions

**Q: Why ZooKeeper for metadata historically, and why KRaft replaces it?**
> ZooKeeper was used for distributed coordination (leader election, broker liveness, partition metadata). Problems: (1) Operational complexity — separate ZK cluster to maintain, (2) ZK session semantics leak into Kafka (ZK session = broker session), (3) ZK bottleneck at ~200K partitions (write throughput limited), (4) Controller recovery required full ZK state reload. KRaft uses Raft consensus directly in Kafka: controller nodes form Raft quorum, metadata stored as events in `__cluster_metadata` topic. Simpler ops, scales to 1M+ partitions, faster controller failover.

**Q: How would you design multi-datacenter replication?**
> Active-Active: both datacenters accept writes to their respective clusters. MirrorMaker 2 replicates between them asynchronously. Problem: message ordering and deduplication across datacenters is complex. Offset translation needed (same message = different offset in each cluster). Preferred: Active-Passive. Primary cluster handles all writes. MirrorMaker 2 replicates to DR cluster. RPO = replication lag (~seconds). On failover: consumers redirect to DR cluster, resume from translated offset. For global: active-active with application-level conflict resolution (CRDT-style).

**Q: When would you choose Pulsar over Kafka?**
> Pulsar: compute-storage separation (broker is stateless — only serves; Bookkeeper handles storage). Advantages: broker scaling without data rebalancing, multi-tenancy built-in (namespaces, tenants), native geo-replication. Kafka advantages: larger ecosystem, battle-tested at extreme scale (LinkedIn), better stream processing integration (Kafka Streams, ksqlDB). Choose Pulsar for: multi-cloud, true multi-tenancy, geo-active replication. Choose Kafka for: highest throughput, stream processing, most mature ecosystem.

---

## Scaling Evolution Questions

**Q: How does your design evolve from 100 MB/sec to 10 GB/sec?**
> Phase 1 (100 MB/sec): 3 brokers, 50 topics, 200 partitions. Single AZ OK. ZooKeeper (or KRaft 3 nodes). Phase 2 (1 GB/sec): 30 brokers, 1000 topics, 10K partitions. Multi-AZ, rack-aware replicas. KRaft required (ZK bottlenecks). Dedicated controller nodes. Tiered storage for retention cost. Phase 3 (10 GB/sec): 300+ brokers. MirrorMaker for multi-region. Schema registry HA. Consumer group fan-out becomes dominant — consumer infrastructure scales independently from broker infrastructure.

**Q: How do you handle topic proliferation (100K topics)?**
> 100K topics × 12 partitions = 1.2M partitions. At 1M partitions, even KRaft starts showing metadata latency (metadata log is large, snapshots are slow). Solutions: (1) Topic lifecycle management — delete unused topics (TTL on topic creation), (2) Shared topics with routing key in message value — reduce topic count by multiplexing, (3) Hierarchical routing — `service.entity.eventtype` naming with shared topics per service, (4) Dedicated admin tooling — approve topic creation, enforce partition count limits.

---

## Common Mistakes (Trap Questions)

**Trap: "Why not just use Kafka for request-reply RPC?"**
> Kafka is one-directional. Request-reply requires: (1) correlation ID in request header, (2) reply topic per client or shared reply topic, (3) consumer polling reply topic. Adds complexity: multiple topics per interaction, correlation management, timeout handling. For RPC: use gRPC or REST. Kafka for RPC is an anti-pattern — use it only when message durability or fan-out is needed.

**Trap: "Can you delete a specific message from Kafka?"**
> No, by design. Log is immutable append-only. Options: (1) Produce a tombstone (null value for key) — works only for compacted topics, eventually removes the key, (2) Topic deletion and recreation — destructive and disruptive, (3) GDPR compliance: encrypt value with per-user key, "delete" by destroying the key — crypto-shredding pattern.

**Trap: "Increasing partition count is always safe."**
> No. Risks: (1) Key-based routing breaks — same key now routes to different partition (hash mod changes). Consumers processing by key lose ordering. (2) Consumer rebalance — all consumer groups rebalance when partition count changes. Processing pauses for all groups. (3) Cannot decrease partition count — only increase. Incorrect partition count is permanent without topic deletion/recreation.

---

## Staff/Principal Engineer Discussion Points

**System-Level Tradeoffs:**
- Kafka chooses CP in CAP for metadata (controller) but AP for data plane (clients retry to new leader)
- The log abstraction is the conceptual breakthrough — not invented for Kafka (databases use WAL, file systems use journals) but applied brilliantly for messaging
- Why sequential writes at "disk speed" approach "RAM speed": OS read-ahead + page cache converts sequential disk access to memory bandwidth (~10 GB/sec DRAM vs 3 GB/sec NVMe sequential)

**What Would Break First Analysis:**

| At Scale | What Breaks | Signal |
|---|---|---|
| 1M msg/sec | JVM GC pause on leader → ISR shrink → acks=all blocks | `under_replicated_partitions > 0`, GC log pause times |
| 1M partitions | Controller metadata snapshot → slow failover; ZK dead | `active_controller_count = 0` for minutes |
| 10K consumer groups | Group coordinator broker overloaded (group rebalances) | Coordinator broker CPU spike during deploys |
| 100K topics | Metadata response size → client metadata refresh overloads clients | Client metadata fetch timeouts |
| Cross-DC replication | MirrorMaker consumer lag → replication lag → DR RPO violation | MM2 consumer lag on source cluster |

**Architecture Critique:**
- **Partition count rigidity** is Kafka's biggest operational pain point. Repartitioning requires application redesign (key routing changes). Every other scaling axis (brokers, consumers, topics) is flexible; partitions are not.
- **Consumer group rebalance** is stop-the-world. For systems with 1000+ consumer instances, a rolling deploy causes multiple full rebalances → minutes of consumer pause. Cooperative rebalance (KIP-429) is the mitigation but requires client library upgrade.
- **JVM is the enemy of predictable latency**. GC pauses cause ISR shrinkage, acks=all blocks, follower replication lag. Kafka's Go/Rust reimplementations (Redpanda, WarpStream) solve this at the cost of ecosystem maturity.

---

## Senior vs Staff vs Principal Lens

| Engineer Level | Focus |
|---|---|
| Senior | "How does Kafka guarantee ordering?" "Explain ISR." "What is consumer lag?" |
| Staff | "How would you scale from 1 to 100 brokers?" "Design exactly-once semantics." "What are the tradeoffs of pull vs push?" |
| Principal | "Where would you replace Kafka with a different abstraction?" "How do you evolve a streaming platform to support global active-active?" "What are the fundamental limits of the log-structured approach?" |

---

## Interview Discussion Points

- **What would you change about Kafka if you could redesign it?** Mutable partition count (solved in some successors). Compute-storage separation (Pulsar model). Native multi-tenancy. Better native support for exactly-once at consumer level (not just producer)
- **How is Kafka different from a database transaction log?** Database WAL is internal (not exposed to consumers). Kafka's log is the product — external systems read it. Database WAL is ephemeral (short retention). Kafka log is durable (configurable long retention). Database WAL is for recovery; Kafka log is for event distribution
- **Can Kafka be a source of truth?** Yes, with log compaction. Compacted topic retains latest value per key indefinitely — equivalent to an event-sourced database. Used for change data capture (CDC) and state replication
- **What makes Kafka hard to operate?** (1) Partition reassignment is manual and disruptive, (2) Consumer group rebalances during deploys, (3) JVM tuning for GC, (4) Monitoring requires multiple tools (JMX + Burrow + custom exporters), (5) Data archival (tiered storage) is complex to configure correctly
