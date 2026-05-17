# 10 — Message Queue Design: Pastebin / Code Sharing Platform

---

## Objective

Design the Kafka messaging layer for async processing. Cover topic configuration, partitioning strategy, consumer group design, ordering guarantees, exactly-once semantics, retry handling, and dead letter queue patterns.

---

## Why Kafka (Not RabbitMQ or SQS)?

| Criterion | Kafka | RabbitMQ | SQS |
|-----------|-------|---------|-----|
| Message retention | Configurable (7 days) — replay possible | Messages deleted on ACK | Up to 14 days |
| Throughput | Millions/second | ~100K/second | ~10K/second |
| Consumer groups | Multiple independent groups per topic | Queue-based (one consumer per message) | Competing consumers only |
| Ordering | Per-partition, per-key | Per-queue | Only FIFO queues |
| Replay on consumer failure | Yes — seek to earlier offset | No | No (DLQ manual requeue) |
| Operational complexity | High | Medium | Low (managed) |
| Use case fit | Event streaming, audit log, replay | Task queues, RPC | Simple task offloading |

**Kafka chosen because:**
- Multiple consumer groups (Cleanup, Analytics, Moderation) must independently consume the same `paste.created` event — Kafka fanout is natural
- Message replay is critical: if the Moderation consumer is down for 2 hours, it must process all events from the last 7 days on recovery
- Analytics aggregation benefits from ordered per-paste events

**When RabbitMQ/SQS would be better:** If only one consumer per event type and no replay requirement (e.g., simple email notification queue).

---

## Kafka Cluster Configuration

```yaml
Cluster:
  brokers: 3 (minimum for production HA)
  min.insync.replicas: 2
  replication.factor: 3
  
Producer settings:
  acks: all           # Wait for all in-sync replicas
  retries: Integer.MAX_VALUE
  max.in.flight.requests.per.connection: 5
  enable.idempotence: true

Consumer settings:
  auto.offset.reset: earliest
  enable.auto.commit: false  # Manual commit for at-least-once
  isolation.level: read_committed
```

---

## Topic Catalog

### Topic: `paste.created`

```
Purpose:      Notify all downstream contexts of a new paste
Producer:     Paste Module (via Outbox Poller)
Consumers:    Cleanup Module, Analytics Module, Moderation Module
Partitions:   12
Replication:  3
Retention:    7 days
Compaction:   None (time-based retention)
Key:          pasteId (UUID)
```

**Partition assignment by key:** `hash(pasteId) % 12`
- All events for the same paste → same partition → ordered delivery
- Cleanup module receives paste.created before paste.expired for the same paste

### Topic: `paste.viewed`

```
Purpose:      Track paste view events for analytics
Producer:     Paste Module (fire-and-forget)
Consumers:    Analytics Module
Partitions:   12
Replication:  3
Retention:    3 days (analytics only — shorter retention acceptable)
Key:          pasteId
```

**High-volume topic.** At 400 RPS reads with 10:1 CDN hit, only 40 RPS events reach Kafka. Peak: 400 events/second. 12 partitions × 33 events/partition/second — well within Kafka capacity.

**Producer strategy: fire-and-forget for views**
- View events are best-effort (losing a few view events is acceptable)
- Producer `acks=1` (leader ack only) — faster, lower durability
- Contrast: paste.created uses `acks=all` (must not be lost)

### Topic: `paste.deleted`

```
Purpose:      Trigger S3 cleanup and CDN invalidation
Producer:     Paste Module
Consumers:    S3 Cleanup Consumer, CDN Invalidator
Partitions:   6
Replication:  3
Retention:    7 days
Key:          pasteId
```

### Topic: `paste.expired`

```
Purpose:      Signal that a paste's lifetime has ended (produced by Cleanup Module)
Producer:     Cleanup Module (DB poll job)
Consumers:    Paste Module (to mark paste as deleted and trigger paste.deleted)
Partitions:   6
Replication:  3
Retention:    7 days
Key:          pasteId
```

### Topic: `paste.abuse-flagged`

```
Purpose:      Signal that content moderation flagged a paste
Producer:     Moderation Module
Consumers:    Paste Module, Alert Service
Partitions:   3
Replication:  3
Retention:    30 days (for audit)
Key:          pasteId
```

### Topic: `paste.cleanup.dlq` (Dead Letter Queue)

```
Purpose:      Hold events that failed processing after max retries
Producer:     Any consumer that exhausts retries
Consumers:    Ops Alert Service, manual triage tooling
Partitions:   3
Replication:  3
Retention:    30 days
Key:          originalTopic + pasteId
```

### Topic: `audit.events`

```
Purpose:      Immutable audit log for all security-relevant events
Producer:     All modules
Consumers:    Audit Archive Service (writes to S3/Glacier)
Partitions:   6
Replication:  3
Retention:    90 days in Kafka, then archived to S3 for 7 years
Key:          eventType
```

---

## Consumer Group Design

### Cleanup Module Consumer Group

```
Group ID: cleanup-expiry-consumer-group
Topic: paste.expired
Instances: 2 (active-active for HA)
Processing: At-least-once, idempotent
  - On receipt: check if paste already deleted → skip if yes
  - If not deleted: soft-delete in DB, publish paste.deleted
```

### S3 Cleanup Consumer Group

```
Group ID: s3-cleanup-consumer-group
Topic: paste.deleted
Instances: 2
Processing: At-least-once
  - Retry policy: 5 retries with exponential backoff (1s, 2s, 4s, 8s, 16s)
  - On 5th failure: publish to paste.cleanup.dlq
  - Idempotent: S3 DeleteObject is idempotent (safe to retry)
```

### CDN Invalidator Consumer Group

```
Group ID: cdn-invalidator-group
Topic: paste.deleted
Instances: 1 (CDN invalidation API has rate limits)
Processing: At-least-once
  - CloudFront: max 3,000 invalidation paths/second (global limit)
  - Batch paths: collect up to 100 paths, submit as one invalidation request
  - On rate limit: backoff and retry
```

### Analytics Consumer Groups

```
Group ID: analytics-view-aggregator
Topic: paste.viewed
Instances: 4
Processing: At-least-once (losing a view event is acceptable)
  - Buffer views in memory (100ms window)
  - Batch write to paste_view_events table
  - Use COPY command for bulk inserts (10x faster than individual INSERTs)

Group ID: analytics-creation-stats
Topic: paste.created
Instances: 2
Processing: At-least-once
  - Increment daily creation counters in Redis
  - Flush to analytics DB every 5 minutes
```

### Moderation Consumer Group

```
Group ID: moderation-scanner
Topic: paste.created
Instances: 4
Processing: At-least-once
  - Fetch content from S3 using s3Key from event
  - Run blocklist check, pattern matching
  - If flagged: publish to paste.abuse-flagged
  - If clean: no action (optional: mark in moderation status table)
  - Slow consumer warning: ML scanning can take 500ms — handle with sufficient consumer instances
```

---

## Message Schema Versioning

**Problem:** Consumer and producer may be deployed at different times. Producer adds new fields → consumer must handle gracefully.

**Strategy: Avro + Schema Registry**

```
Schema Registry: Confluent Schema Registry
  - Each topic has a registered schema
  - Schema evolution: BACKWARD_COMPATIBLE (new optional fields only)
  - Consumers tolerate unknown fields

Example schema for paste.created (v1.0 → v1.1 evolution):
  v1.0: { pasteId, shortKey, ownerId, expiresAt }
  v1.1: adds { language } (optional, default "plaintext")
  
  v1.0 consumers read v1.1 messages → language field ignored (backward compat)
  v1.1 consumers read v1.0 messages → language field defaults to "plaintext"
```

**Alternative: JSON with explicit version field**
- Simpler (no schema registry to operate)
- Less type-safe — typos in field names are runtime errors
- Acceptable for small teams; prefer Avro for larger teams / higher reliability

---

## Ordering Guarantees Deep Dive

### Within a Paste (per-key ordering)

Kafka guarantees message ordering within a partition. Because we use `pasteId` as the key:

```
All events for paste "abc123" → same partition N

Sequence on partition N:
  [paste.created] → [paste.viewed] → [paste.viewed] → [paste.expired]

Cleanup consumer reads these in order:
  1. paste.created: schedule expiry → OK
  2. paste.expired: delete paste → OK
  ✓ Cleanup never sees paste.expired before paste.created
```

### Cross-Topic Ordering Issue

```
paste.expired published by Cleanup Module → consumed by Paste Module → Paste Module publishes paste.deleted
→ consumed by S3 Cleanup

The ordering across topics (paste.expired → paste.deleted → S3 delete) is achieved by
the sequential processing chain, not by Kafka ordering guarantees.
```

This is acceptable because:
- S3 deletion is idempotent
- If paste.deleted arrives before paste.expired is processed, the delete just happens slightly earlier

---

## Exactly-Once Semantics

### Do We Need Exactly-Once?

| Event | Duplicate Impact | Strategy |
|-------|----------------|---------|
| paste.created | Double-schedules expiry | Idempotent: `INSERT ... ON CONFLICT DO NOTHING` |
| paste.viewed | Over-counts views by 1 | Acceptable (best-effort analytics) |
| paste.deleted | Double S3 delete | Idempotent: S3 DeleteObject OK if object missing |
| paste.expired | Double soft-delete | Idempotent: `UPDATE` is idempotent if already deleted |

**Conclusion:** All consumers are idempotent. At-least-once delivery is sufficient; exactly-once adds Kafka transaction complexity unnecessarily.

**Exception:** If view count tracking becomes billing-relevant (SaaS plan with view limits), then exactly-once becomes required. Kafka transactions + idempotent producer for paste.viewed.

---

## Consumer Lag Monitoring

```
Alert: consumer_lag > 10,000 for paste.created (cleanup-expiry-consumer-group)
  → Pastes may not be cleaned up on time
  → Action: scale up cleanup instances

Alert: consumer_lag > 50,000 for paste.viewed (analytics-view-aggregator)
  → View count updates severely delayed (hours behind)
  → Action: scale up analytics instances or reduce analytics processing time

Alert: paste.cleanup.dlq message count > 0
  → S3 deletions failing — investigate S3 permissions/availability
  → Page on-call immediately
```

**Dashboard:** Kafka consumer lag per group per partition, visualized in Grafana.

---

## Kafka Reliability Patterns Summary

| Pattern | Applied To | Purpose |
|---------|-----------|---------|
| Transactional Outbox | paste.created producer | Prevent event loss on app crash |
| At-least-once delivery | All consumers | Simpler than exactly-once, idempotent consumers |
| Manual commit | All consumers | Don't commit until processing succeeds |
| Exponential backoff retry | S3 cleanup consumer | Handle transient S3 failures |
| Dead Letter Queue | S3 cleanup consumer | Capture unrecoverable failures |
| Consumer group isolation | Cleanup vs Analytics | Independent scaling and failure isolation |
| Schema Registry | All topics | Schema evolution without breaking changes |
| `FOR UPDATE SKIP LOCKED` | DB-based expiry scheduler | Concurrent safe batch processing |

---

## Interview Discussion Points

- Why use `acks=all` for paste.created but `acks=1` for paste.viewed?
- What is the Transactional Outbox Pattern and why is it better than Kafka transactions for this use case?
- How do you prevent the same paste from being expired twice by two concurrent cleanup consumer instances?
- What happens if the Analytics consumer falls behind by 6 hours — does it affect the user experience?
- How does Schema Registry prevent consumer failures when a new field is added to a Kafka message?
- If Kafka goes down for 2 hours, what is the impact on paste creation? (Answer: pastes still created in DB/S3; outbox buffers events; replayed on Kafka recovery)
- Why does CDN invalidation use batch grouping of 100 paths? What problem does this solve?
