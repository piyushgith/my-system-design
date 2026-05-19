# 03 — DDD Bounded Contexts: Kafka-like Event Streaming System

---

## Objective

Define bounded contexts, their responsibilities, internal models, and integration contracts. Establish where context boundaries lie and how inter-context communication happens.

---

## Architecture Choice: Modular Monolith Initially, Extract Later

**Why start modular monolith?**
- Broker is a single OS process — partitioning into microservices would add network hops in the critical data path
- Controller, replication, and storage are tightly coupled through shared state (partition leadership, ISR)
- Operational simplicity: one binary to deploy, monitor, and debug

**Extract to dedicated services when:**
- Schema Registry grows to enterprise catalog scope (versioning, governance, lineage)
- Tiered storage becomes a separate data lifecycle product
- Admin/governance tooling needs independent deployment cadence

---

## Bounded Contexts

### 1. Storage Context

**Responsibility**: Raw log management — write, read, compact, retain, and expire log segments.

**Owns:**
- Log segment lifecycle (active, closed, compacted, deleted)
- Segment rolling logic (size/time triggers)
- Offset-to-file-position index management
- Log compaction background process
- Retention enforcement (delete old segments)
- Zero-copy read path (sendfile)

**Key Entities:** `LogSegment`, `PartitionLog`, `OffsetIndex`, `TimeIndex`

**Does NOT own:** Replication, partition leadership, consumer offsets

**Language:**
- "append to log"
- "roll segment"
- "compact partition log"
- "enforce retention"
- "read from offset"

---

### 2. Replication Context

**Responsibility**: Keep partition replicas consistent with the leader. Manage ISR membership.

**Owns:**
- Follower fetch loop (replica fetches from leader's log)
- ISR membership decisions (add/remove based on lag threshold)
- High-watermark advancement (only after all ISR acknowledge)
- Leader epoch tracking and fencing
- Replica assignment to brokers

**Key Entities:** `ReplicaManager`, `Partition (replication view)`, `IsrManager`, `LeaderEpoch`

**Does NOT own:** Physical log storage (delegates to Storage Context), consumer group management

**Integration with Storage Context:**
- Follower reads leader's log via same storage read path
- Leader advances HWM only after storage confirms writes to ISR

**Language:**
- "ISR shrink/expand"
- "high-watermark advance"
- "follower caught up"
- "leader epoch fence"
- "replica throttle"

---

### 3. Metadata & Coordination Context (Controller)

**Responsibility**: Single source of truth for cluster topology. Drives partition leadership and broker lifecycle.

**Owns:**
- Broker registration and liveness tracking
- Topic/partition creation and deletion
- Partition leader election (on broker failure)
- Partition reassignment (rebalancing across brokers)
- Controller election via Raft (KRaft)
- Cluster metadata log (event-sourced metadata state)

**Key Entities:** `ClusterMetadata`, `BrokerRegistration`, `PartitionLeaderAndIsr`, `TopicRecord`, `PartitionRecord`

**Does NOT own:** Log storage, consumer groups, schema validation

**Integration with Replication Context:**
- Controller sends `LeaderAndIsr` requests to brokers after leader election
- Brokers report ISR changes to controller

**Language:**
- "elect leader"
- "fence broker"
- "reassign partitions"
- "metadata epoch"
- "controller epoch"

---

### 4. Network & Protocol Context

**Responsibility**: Handle client connections, parse Kafka wire protocol, enforce quotas, route to correct handler.

**Owns:**
- Socket server (acceptor/processor threads)
- Kafka wire protocol encoding/decoding (ApiKey, ApiVersion, correlation IDs)
- Request queue management (per-API priority queues)
- Per-client connection state
- Quota enforcement (producer bandwidth, consumer bandwidth, request rate)
- SSL/TLS termination
- SASL authentication handshake

**Key Entities:** `KafkaChannel`, `Request`, `Response`, `QuotaManager`, `ApiVersionsRegistry`

**Does NOT own:** Business logic for produce/fetch — delegates to Storage and Replication contexts

**Language:**
- "accept connection"
- "decode request"
- "throttle client"
- "send response"
- "SASL handshake"

---

### 5. Consumer Group Context

**Responsibility**: Manage consumer group membership, partition assignment, and offset tracking.

**Owns:**
- Group coordinator (elected via consistent hash of groupId on broker cluster)
- JoinGroup/SyncGroup protocol
- Heartbeat management and session timeout
- Partition assignment strategies (range, round-robin, sticky, cooperative-sticky)
- Offset commit and fetch to `__consumer_offsets`
- Group state machine (Empty → PreparingRebalance → CompletingRebalance → Stable → Dead)

**Key Entities:** `ConsumerGroup`, `GroupMember`, `Assignment`, `OffsetCommit`

**Does NOT own:** Log storage (offsets stored in Kafka topic), partition leadership

**Integration with Storage Context:**
- `__consumer_offsets` is a compacted topic — Storage context stores it like any other partition

**Language:**
- "trigger rebalance"
- "assign partitions"
- "commit offset"
- "heartbeat timeout"
- "group epoch"

---

### 6. Schema Registry Context (Separate Service)

**Responsibility**: Validate and version message schemas. Enforce compatibility contracts.

**Owns:**
- Schema storage (Avro/Protobuf/JSON Schema definitions)
- Schema version management per subject (topic-key, topic-value)
- Compatibility checking (BACKWARD, FORWARD, FULL, NONE)
- Schema ID assignment
- REST API for schema registration and retrieval

**Key Entities:** `Schema`, `Subject`, `SchemaVersion`, `CompatibilityConfig`

**Does NOT own:** Message routing, log storage, consumer groups

**Integration with Broker:**
- Not in hot write path — producers fetch schema ID on startup, embed in message header
- Brokers optionally validate on produce (adds latency — typically disabled for performance)

**Language:**
- "register schema"
- "check compatibility"
- "resolve schema by ID"
- "subject evolution"

---

## Context Map

```
┌─────────────────────────────────────────────────────────────────────┐
│                        BROKER PROCESS                                │
│                                                                      │
│  ┌───────────────────┐         ┌───────────────────────────────┐    │
│  │  Network &        │         │  Metadata & Coordination      │    │
│  │  Protocol Context │──calls──│  Context (Controller)         │    │
│  └────────┬──────────┘         └───────────────────────────────┘    │
│           │                                │                         │
│           │                                │LeaderAndIsr             │
│           ▼                                ▼                         │
│  ┌────────────────────┐    ┌───────────────────────────────┐        │
│  │  Replication       │    │  Consumer Group               │        │
│  │  Context           │    │  Context                      │        │
│  └────────┬───────────┘    └───────────────┬───────────────┘        │
│           │delegates read/write             │reads/writes            │
│           ▼                                ▼                         │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │              Storage Context (Partition Logs)                  │ │
│  └────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘

┌──────────────────┐
│  Schema Registry │◄── Producer/Consumer (optional validation)
│  Context         │
│  (Separate Svc)  │
└──────────────────┘
```

---

## Integration Contracts Between Contexts

| From | To | Integration | Contract |
|---|---|---|---|
| Network Context | Storage Context | Direct call (in-process) | Produce request: `(topicPartition, batch) → offset`; Fetch request: `(topicPartition, offset, maxBytes) → records` |
| Network Context | Consumer Group Context | Direct call (in-process) | JoinGroup, SyncGroup, OffsetCommit, OffsetFetch |
| Replication Context | Storage Context | Direct call | Follower fetch: `(topicPartition, offset) → records` |
| Controller Context | Replication Context | `LeaderAndIsr` request (wire protocol) | `{partition, leader, isr, leaderEpoch, partitionEpoch}` |
| Consumer Group Context | Storage Context | Same produce/fetch API | `__consumer_offsets` topic read/write |
| Producer Client | Schema Registry | REST HTTP | `POST /subjects/{subject}/versions`, `GET /schemas/ids/{id}` |

---

## Anti-Corruption Layers

**Producer client ↔ Schema Registry:**
Producer embeds schema ID (4 bytes) in message value prefix before sending to broker. Broker treats this as opaque bytes — no schema awareness required in broker hot path. This is the ACL: schema semantics don't leak into the transport layer.

**Consumer Group ↔ Storage:**
Offsets are stored in `__consumer_offsets` using the same storage abstraction as user topics. The consumer group context doesn't need special offset DB — it just writes Kafka records. Clean separation.

**Controller ↔ Brokers:**
Controller communicates via the same Kafka wire protocol as clients. Brokers don't have special controller-only code paths for most operations — clean protocol boundary.

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| Schema Registry as separate service | Independent deploy/scale, language-neutral | Extra network hop; schema validation not in broker write path |
| Consumer Group Context co-located with broker | Low latency offset commits, no network hop | Coordinator is a single broker — hot spot risk for large group count |
| Controller as separate process (KRaft) | Avoids controller bottleneck from broker load | Need to deploy controller quorum separately |
| Storage as shared context within broker | Zero-copy, no IPC overhead | All contexts share broker heap/GC pressure |

---

## Interview Discussion Points

- **Why is the controller not a distributed service?** Single active controller (elected via Raft) is simpler and sufficient — metadata operations are low-frequency. Distributed controller would add complexity without benefit
- **What is the group coordinator?** A specific broker elected to manage a consumer group (via hash(groupId)). Not a global service — each broker coordinates different groups
- **Why not use a distributed lock service (etcd/Consul) for partition leadership?** Dedicated coordinator adds operational complexity and a network round-trip per leadership decision. ISR + controller handles this with less moving parts
- **How does schema registry ensure compatibility without being in the write path?** Producer validates at schema registration time, not per-message. Schema ID in header lets consumers fetch schema lazily
- **What happens when the group coordinator broker fails?** New coordinator elected via consistent hashing. Consumers reconnect and re-do JoinGroup. Committed offsets survive (stored in __consumer_offsets)
