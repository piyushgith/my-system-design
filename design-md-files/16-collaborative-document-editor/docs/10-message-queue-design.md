# 10 — Message Queue Design

## Objective
Define the Kafka topic architecture, consumer group strategy, message schema design, ordering guarantees, and failure handling for the Collaborative Document Editor's event backbone.

---

## Why Kafka as the Event Backbone

| Requirement | How Kafka Satisfies It |
|---|---|
| Ordered op delivery per document | Kafka partitions guarantee ordering within partition; docId as partition key |
| Durable event log (event sourcing) | Kafka's immutable, replicated log is the event store |
| High throughput (10 M ops/sec) | Horizontal partition scaling; batching; compression |
| Multiple independent consumers | Consumer groups allow Snapshot Service, Search, Audit to consume independently |
| Replay capability | Consumer groups can reset offsets to replay from any point in time |
| Backpressure handling | Consumer controls pull rate; producers are not blocked by slow consumers |

**Alternatives considered:**
- **RabbitMQ**: Push-based, not log-based; poor replay support; lower throughput ceiling
- **Redis Streams**: Good for real-time fan-out but limited retention; no cross-datacenter replication
- **AWS SQS/SNS**: Managed, but no ordering guarantee within a topic at scale; no log replay
- **Pulsar**: Viable alternative to Kafka; tiered storage solves the retention problem better; chosen Kafka for operational familiarity

---

## Topic Architecture

### Core Topics

| Topic | Partitions | Retention | Replication | Purpose |
|---|---|---|---|---|
| `doc-ops` | 1,000 | 7 days | 3 | Primary op stream; source of truth |
| `doc-ops-hot` | 100 | 24 hours | 3 | Dedicated topic for hot documents (> 1K ops/sec) |
| `doc-snapshots` | 100 | 90 days | 3 | Snapshot creation events (lightweight metadata) |
| `doc-metadata` | 50 | 30 days | 3 | Document create/update/delete events |
| `presence` | 200 | 1 hour | 2 | Ephemeral presence events (for cross-region sync) |
| `comments` | 50 | 30 days | 3 | Comment create/resolve events |
| `permissions` | 50 | 30 days | 3 | Permission grant/revoke events |
| `audit` | 100 | 1 year | 3 | Immutable audit log |
| `exports` | 20 | 7 days | 3 | Export job requests and completions |
| `notifications` | 50 | 7 days | 3 | User notification events |
| `gdpr-requests` | 10 | 30 days | 3 | GDPR erasure and export requests |

---

## Partition Strategy

### Primary Partition Key: documentId

For `doc-ops`, the partition key is `documentId` (hash of UUID → partition number).

**Why documentId and not userId?**
- All ops for the same document must be in the same partition to guarantee ordering
- Using userId would scatter the same document's ops across partitions, breaking ordering

**Hot document problem:**
- A single document generating 10 K ops/sec concentrated on one partition creates an imbalanced partition
- Solution: hot documents are moved to `doc-ops-hot` topic with their own dedicated partitions
- Hot tier detection: Collaboration Service monitors per-doc op rate; above 1,000 ops/sec, routes to `doc-ops-hot`

**Partition count evolution:**
- Start: 100 partitions (sufficient for launch)
- At scale: 1,000 partitions (Kafka requires restart for partition count increase; plan ahead)
- Avoid increasing partitions frequently — it breaks partition-based order guarantees for in-flight messages during rebalance

---

## Message Schema (Avro with Schema Registry)

### OperationApplied Event

```json
{
  "type": "record",
  "name": "OperationApplied",
  "namespace": "com.docs.events.v1",
  "fields": [
    {"name": "eventId", "type": "string"},
    {"name": "documentId", "type": "string"},
    {"name": "seq", "type": "long"},
    {"name": "parentSeq", "type": "long"},
    {"name": "authorId", "type": "string"},
    {"name": "operation", "type": {
      "type": "record",
      "name": "Operation",
      "fields": [
        {"name": "type", "type": {"type": "enum", "name": "OpType",
          "symbols": ["INSERT", "DELETE", "RETAIN", "FORMAT"]}},
        {"name": "position", "type": "int"},
        {"name": "content", "type": ["null", "string"], "default": null},
        {"name": "length", "type": ["null", "int"], "default": null},
        {"name": "attributes", "type": ["null", {"type": "map", "values": "string"}], "default": null}
      ]
    }},
    {"name": "clientId", "type": "string"},
    {"name": "clientSeq", "type": "int"},
    {"name": "appliedAt", "type": {"type": "long", "logicalType": "timestamp-millis"}},
    {"name": "schemaVersion", "type": "int", "default": 1}
  ]
}
```

**Schema evolution rules:**
- New optional fields: backward-compatible; add with default value
- Never remove or rename fields without version bump
- Schema Registry enforces compatibility check (BACKWARD or FULL compatibility mode)
- Consumers must handle unknown fields gracefully (ignore unknown; don't fail)

---

## Consumer Groups

### Consumer Group: snapshot-service
- **Topic:** `doc-ops`
- **Consumer count:** 100 (one per partition subset)
- **Processing:** Accumulate ops; trigger snapshot creation every 5 minutes or 1,000 ops per document
- **Lag tolerance:** High (can fall behind during peak; catch up when load drops)
- **Failure behavior:** On consumer failure, Kafka reassigns partitions. Consumer re-reads from last committed offset. Snapshot Service is idempotent — re-processing an op it already included in a snapshot is a no-op (op seq < snapshot.at_seq is skipped).

### Consumer Group: search-indexer
- **Topic:** `doc-snapshots` (not `doc-ops` — indexes on snapshot events to avoid indexing every individual op)
- **Consumer count:** 20
- **Processing:** On `SnapshotCreated` event: fetch snapshot from S3, extract text, upsert into Elasticsearch
- **Lag tolerance:** Very high (search index can be minutes behind without user-visible impact)

### Consumer Group: permission-cache-invalidator
- **Topic:** `permissions`
- **Consumer count:** 10
- **Processing:** On `PermissionRevoked` event: DEL Redis key `perm:{userId}:{documentId}`; on `PermissionGranted`: optional pre-warm
- **Lag tolerance:** Low — revocations should be processed within 5 seconds

### Consumer Group: notification-processor
- **Topic:** `doc-metadata`, `comments`, `permissions`
- **Consumer count:** 50
- **Processing:** Translate events into user notifications (email digest, push notification, in-app)
- **Lag tolerance:** Medium (5–30 seconds acceptable for notification delivery)

### Consumer Group: audit-writer
- **Topic:** All topics (wildcard subscription: `doc.*`, `permissions`, `comments`)
- **Consumer count:** 20
- **Processing:** Write all events to append-only audit PostgreSQL table
- **Lag tolerance:** Medium (audit log is not real-time critical)

### Consumer Group: postgres-batch-writer
- **Topic:** `doc-ops`
- **Consumer count:** 50 (matching partition subset)
- **Processing:** Batch writes to `op_log` PostgreSQL table using COPY command (10,000 rows/batch)
- **Lag tolerance:** Very low (< 500 ms lag needed so that PostgreSQL op_log is current for version history queries)

---

## Dead Letter Queue Strategy

When a consumer fails to process a message after 3 retries:

```
doc-ops → [Consumer fails 3x] → doc-ops-dlq
doc-snapshots → [Consumer fails 3x] → doc-snapshots-dlq
```

**DLQ topics:** Each topic has a corresponding `-dlq` topic with 365-day retention.

**DLQ processing:**
- Alert fires immediately when any message reaches DLQ
- On-call engineer reviews DLQ messages in DLQ viewer dashboard
- Messages are replayed after fix deployment using a manual replay tool (re-publishes DLQ messages to the original topic with `X-Retry: true` header)

**Idempotency requirement:** All consumers must be idempotent for DLQ replay to be safe. Idempotency key: `{eventId}` stored in PostgreSQL dedup table per consumer group.

---

## Ordering Guarantees

| Scope | Guarantee | Mechanism |
|---|---|---|
| Ops within one document | Total order | Single partition per document; seq assigned by Collaboration Service |
| Events within one consumer | Sequential within partition | Single consumer thread per partition |
| Cross-document ops | No guarantee | Not needed; documents are independent |
| Presence events | Best-effort | Lower retention (`presence` topic); not order-critical |

**Exactly-once delivery (EOS):**
For the `postgres-batch-writer`, exactly-once semantics are critical to avoid duplicate op_log entries:
- Kafka producer: `enable.idempotence=true`; `transactional.id` configured per producer instance
- Consumer: read committed isolation; atomic `COPY` within a Kafka transaction
- This ensures each op appears in op_log exactly once, even during producer/consumer retries

---

## Message Retention & Storage

| Topic | Retention | Storage Estimate |
|---|---|---|
| `doc-ops` (1,000 partitions) | 7 days | ~240 TB (compressed at 3×) |
| `doc-ops-hot` | 24 hours | ~5 TB |
| `doc-snapshots` | 90 days | ~50 TB |
| All other topics | 7–30 days | ~20 TB total |

**Cost reduction strategies:**
- Kafka compression: Snappy (good balance of speed and ratio); 3× reduction on text ops
- Tiered storage: Kafka 3.x + S3 tiered storage for segments older than 2 days; reduces broker disk cost by 70%
- Compaction: `doc-metadata` topic uses log compaction (retain latest value per document key)

---

## Cross-Region Kafka Replication

For multi-region deployment, Kafka MirrorMaker 2 replicates topics:

```
US-East Kafka ──MirrorMaker 2──► EU-West Kafka
                                ──MirrorMaker 2──► APAC Kafka
```

- `doc-ops`: Replicated to all regions (needed for version history globally)
- `presence`: NOT replicated cross-region (ephemeral; latency makes cross-region presence meaningless)
- Replication lag: 100–300 ms across regions
- Consumers in each region consume from their local Kafka cluster

---

## Backpressure Handling

**Scenario:** Snapshot Service falls behind during a traffic spike.

1. Kafka consumer lag increases (visible in Grafana)
2. Alert fires at > 5-minute lag
3. Snapshot Service autoscales: Kubernetes HPA adds more consumer pods
4. Each new pod takes ownership of additional partitions (Kafka rebalances)
5. Kafka's retention window (7 days) provides a buffer before data loss occurs

**If retention is exceeded (catastrophic lag):**
- Kafka commits offsets to earliest; processes from beginning of retention window
- Snapshot Service processes ops in order; already-snapshotted ops are skipped (idempotent)
- This is a degraded state; recovery prioritizes documents with most recent user activity first

---

## Interview Discussion Points
- Why is `documentId` the correct Kafka partition key rather than `userId` or a random hash?
- What is the risk of increasing Kafka partition count after launch, and how do you mitigate it?
- How does Kafka's exactly-once semantics interact with the Collaboration Service's sequence number assignment?
- If the `postgres-batch-writer` consumer group falls 1 hour behind, what is the user-facing impact?
- How do you implement ordered cross-region op delivery when MirrorMaker introduces replication lag?
- What is the storage cost of retaining `doc-ops` for 7 days at 10 M ops/sec, and how would you reduce it while maintaining replay capability?
