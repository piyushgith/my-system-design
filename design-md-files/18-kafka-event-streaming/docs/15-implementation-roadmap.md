# 15 — Implementation Roadmap: Kafka-like Event Streaming System

---

## Objective

Define a phased implementation plan from minimal viable log-based messaging through production-grade event streaming with exactly-once semantics, multi-tenancy, and global replication.

---

## Phase 0: MVP — Single-Node Log-Based Message Broker (Weeks 1–6)

### Goals
Build a working append-only log broker that a producer can write to and a consumer can read from. No replication. No consumer groups. Prove the core log abstraction works.

### Features
- Single broker, single topic, multiple partitions
- Produce API: append batch of records to partition, return assigned offset
- Fetch API: read records from offset, return up to maxBytes
- Metadata API: return partition count and broker address
- Log segment rolling: roll to new segment at configurable size threshold
- Basic offset index per segment for O(log n) offset lookup
- In-memory metadata: no persistence of topic config (restart = forget)
- Plain TCP socket server (no SSL yet)
- No authentication, no authorization

### Architecture
- Monolithic Java process (no multi-module yet)
- Single thread per request (simple, not production-ready)
- Local filesystem for log segments
- No replication, no ZooKeeper/KRaft

### Tech Stack
- Java 17, Spring Boot (for lifecycle management and config)
- Custom binary protocol (simplified subset of Kafka wire protocol)
- NIO socket server (Netty or raw NIO)
- Log4j2 for structured logging

### Risks
- Getting segment file I/O right (index management, CRC)
- Binary protocol correctness before adding Kafka client compatibility

### Done When
- Producer client (custom) writes 100K msg/sec to local broker
- Consumer client reads and processes, lag tracked correctly
- Segment rolling and basic retention tested

---

## Phase 1: Replication and Multi-Broker (Weeks 7–14)

### Goals
Add replication for durability, multi-broker support, and basic Kafka wire protocol compatibility so standard Kafka clients work.

### Features
- Full Kafka wire protocol (Produce v8, Fetch v11, Metadata v9, ListOffsets v5)
- ISR-based replication (follower pull from leader)
- Leader election via ZooKeeper or in-process Raft (single controller)
- acks=0, acks=1, acks=-1 support
- High watermark management
- Leader epoch tracking and log truncation on failover
- Basic ACL storage (no enforcement yet)
- Config file driven (topic config, replication factor)
- Multi-partition per topic
- Standard Java Kafka clients (`org.apache.kafka:kafka-clients`) connect successfully

### Architecture
- Multi-process (3 broker processes, 1 controller process)
- ZooKeeper for initial controller election (swap for KRaft in Phase 2)
- Network: dedicated acceptor/processor thread model
- Purgatory for delayed produce and fetch operations

### Risks
- ISR replication is the hardest correctness problem — leader epoch fencing, HWM advancement
- Wire protocol compatibility with Kafka clients is nontrivial — use Wireshark to debug protocol mismatch
- Data loss bugs in leader failover are hard to detect without chaos testing

### Done When
- Standard `kafka-console-producer.sh` and `kafka-console-consumer.sh` work against the broker
- Kill leader broker → election happens → producer retries succeed → no data loss (acks=all)
- `kafka-consumer-groups.sh --describe` shows correct lag

---

## Phase 2: Consumer Groups and Offset Management (Weeks 15–20)

### Goals
Support multiple consumer groups with partition assignment, offset tracking, and rebalancing.

### Features
- Group Coordinator: JoinGroup, SyncGroup, Heartbeat, LeaveGroup protocols
- Partition assignment strategies: range, round-robin
- `__consumer_offsets` internal topic for offset persistence
- OffsetCommit and OffsetFetch APIs
- Consumer group state machine (Empty → PreparingRebalance → CompletingRebalance → Stable)
- Session timeout and heartbeat enforcement
- Consumer group describe tooling (lag per partition)

### Architecture
- Group Coordinator co-located with broker (elected by consistent hash of groupId)
- `__consumer_offsets` uses same Storage Context as user topics
- 50 partitions for offset topic (hash(groupId) % 50 determines coordinator)

### Risks
- Consumer group rebalance protocol is stateful and complex — test with concurrent joins/leaves aggressively
- Session timeout edge cases: GC pauses causing false timeouts
- Offset commit during rebalance race conditions

### Done When
- 10 consumers in a group, each reading different partitions
- Kill one consumer → rebalance → remaining consumers cover all partitions
- Restart a consumer → resumes from committed offset
- `kafka-consumer-groups.sh --reset-offsets` works

---

## Phase 3: Security, Schema Registry, and Operations (Weeks 21–28)

### Goals
Production-ready security and observability. Schema Registry for schema validation and evolution.

### Features
- SASL/SCRAM-SHA-256 authentication
- TLS in-transit encryption (TLS 1.3)
- ACL enforcement (topic-level produce/consume ACLs)
- Per-client byte rate quotas
- Schema Registry service (Avro/JSON Schema support)
- Schema compatibility enforcement (BACKWARD compatibility default)
- Prometheus JMX metrics exporter
- Consumer lag monitoring (Burrow-equivalent)
- Grafana dashboards: cluster health, topic throughput, consumer lag
- Audit logging for ACL denials

### Architecture
- Schema Registry as separate Spring Boot service (backed by PostgreSQL)
- Prometheus scraping via JMX Exporter Java agent
- ACL storage in KRaft metadata log (or ZooKeeper for Phase 2 ZK mode)

### Risks
- TLS + SASL adds connection handshake latency — measure impact
- Schema Registry backward compatibility edge cases (adding required fields, removing fields)
- ACL cache invalidation across brokers

### Done When
- SASL/SCRAM client can authenticate, unauthorized client cannot produce/consume
- Schema evolution: V1 schema + V2 backward-compatible schema work transparently
- Grafana shows `under_replicated_partitions = 0` in steady state
- Alert fires when consumer lag exceeds threshold

---

## Phase 4: Exactly-Once, Log Compaction, and Tiered Storage (Weeks 29–40)

### Goals
Add exactly-once delivery semantics, log compaction for CDC use cases, and tiered storage for cost-efficient long-term retention.

### Features
- Idempotent producer (producerId + sequence deduplication)
- Transactional producer (begin/commit/abort across multiple partitions)
- Transaction coordinator service (`__transaction_state` topic)
- Consumer `isolation.level=READ_COMMITTED`
- Log compaction (background cleaner thread, tombstone handling)
- Tiered storage: offload closed segments to S3 with RemoteStorageManager plugin
- Partition reassignment API (admin-triggered, throttled)
- KRaft migration from ZooKeeper (swap controller implementation)

### Architecture
- Transaction coordinator elected per transactionalId (like group coordinator)
- Log cleaner: background priority-queue of dirty partitions, sorted by dirty ratio
- Tiered storage: pluggable `RemoteStorageManager` interface, S3 implementation
- KRaft: replace ZooKeeper dependency with Raft-based metadata log

### Risks
- Transactional producer 2PC is complex — coordinator crash recovery, zombie producer fencing
- Log compaction correctness: must not lose latest value for any key
- Tiered storage read path: consumer transparently reads from remote storage — performance regression for historical reads

### Done When
- Exactly-once processing test: produce 1M messages with random duplicates → consumer sees each key's latest value exactly once
- Log compaction: 1 GB topic with 500K unique keys compacts to < 100 MB
- S3 tiered storage: consumer reads messages from 30-day-old segment served from S3 transparently

---

## Phase 5: Global Scale and Multi-Region (Weeks 41–52)

### Goals
Multi-region replication, advanced consumer features, and enterprise-grade operations.

### Features
- MirrorMaker 2 equivalent: cross-cluster topic replication with offset translation
- Cooperative rebalance (KIP-429): incremental partition assignment to reduce rebalance pause
- Static membership (KIP-345): stable consumer identity across restarts
- Fetch sessions (incremental fetch): reduce metadata overhead for high-partition consumers
- Multi-datacenter rack-aware replica placement
- Automated partition rebalancing based on broker load metrics
- SLA-based topic isolation (dedicated broker pools per SLA tier)
- Consumer group lag alerting with SLO burn rate

### Architecture
- Multi-cluster: primary cluster (write) + DR cluster (MirrorMaker 2 async replication)
- Rack-aware placement: KRaft controller assigns replicas across AZs
- Cooperative rebalance: consumer group protocol v2

### Risks
- Cross-DC offset translation: offset in source cluster ≠ offset in target cluster → consumer must use translated offset on failover
- Cooperative rebalance adds protocol complexity (two-phase revoke + assign)
- Automated partition rebalancing can cause traffic spikes during rebalance

### Done When
- DR cluster replication lag < 5 seconds under normal load
- Consumer failover to DR cluster with < 30-second RTO and < 10-second offset lag
- Rolling deploy with cooperative rebalance: zero consume pause (vs ~30 second pause in standard rebalance)

---

## Phase Summary

| Phase | Duration | Key Milestone |
|---|---|---|
| 0: MVP | Weeks 1–6 | Working append-only log, custom client |
| 1: Replication | Weeks 7–14 | Standard Kafka clients work, leader failover |
| 2: Consumer Groups | Weeks 15–20 | Partition assignment, offset tracking |
| 3: Security + Ops | Weeks 21–28 | SASL, TLS, Schema Registry, Prometheus |
| 4: Exactly-Once + Compaction | Weeks 29–40 | Transactions, log compaction, tiered storage, KRaft |
| 5: Global Scale | Weeks 41–52 | Multi-region, cooperative rebalance, automated ops |

---

## Team Scaling

| Phase | Minimum Team | Focus |
|---|---|---|
| 0–1 | 2 engineers | Core protocol + storage |
| 2 | 3 engineers | Add consumer group protocol specialist |
| 3 | 5 engineers | Add security engineer + SRE |
| 4 | 7 engineers | Add 2 engineers for transactions + tiered storage complexity |
| 5 | 10 engineers | Add DR/multi-region + platform/tooling team |

---

## Interview Discussion Points

- **Why build Kafka from scratch?** Learning exercise only — Apache Kafka is mature and production-proven. In practice, you run Kafka or Confluent Kafka, not build your own. The value is understanding the internals deeply enough to operate, tune, and troubleshoot effectively
- **What would you shortcut to get to production faster?** Skip Phase 4 (exactly-once) until genuinely required. Most systems work fine with at-least-once + idempotent consumers. Exactly-once adds significant operational complexity for marginal benefit unless you're a financial system
- **How do you validate each phase?** Unit tests with Testcontainers, integration tests against the actual broker, chaos tests (kill random brokers, induce disk failures), latency benchmarks with kafka-producer-perf-test.sh equivalent, and correctness tests (verify no data loss under failure)
