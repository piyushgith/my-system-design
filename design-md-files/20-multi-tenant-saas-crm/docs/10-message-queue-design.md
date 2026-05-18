# 10 — Message Queue Design

## Objective

Define the Kafka topic architecture, producer/consumer patterns, message delivery guarantees, partition strategies, consumer group design, and operational considerations for the Multi-Tenant SaaS CRM's asynchronous event processing.

---

## Why Kafka for a CRM

A CRM has significant async processing needs:
- Audit log writes (every mutation generates audit events)
- Search index synchronization (Elasticsearch must stay current)
- Workflow trigger evaluation (conditions evaluated per event)
- Webhook delivery to third-party integrations (external HTTP calls)
- Notification dispatch (email, push, in-app)
- GDPR erasure orchestration (multi-step async workflow)

Kafka is chosen over alternatives because:

| Factor | Kafka | RabbitMQ | Amazon SQS |
|---|---|---|---|
| Message replay | Yes (configurable retention) | No (once consumed, gone) | No |
| Throughput | Millions/sec | ~50K/sec | ~3K/sec per queue |
| Consumer group model | Multiple independent consumers per topic | Point-to-point or pub/sub | Single queue |
| Ordering guarantee | Per-partition ordered | Queue-level ordered | Only FIFO queues |
| Retention for compliance | Yes (30-day audit events) | No | No (max 14 days) |
| Replay after bug | Critical feature for audit | Not possible | Not possible |

**The replay capability is decisive**: If the audit consumer has a bug and writes incorrect audit events, Kafka allows replaying from the offset where the bug was introduced after the fix is deployed. With RabbitMQ, those events are gone.

---

## Kafka Cluster Configuration

### Broker Setup
- **Development**: 1 broker (Kafka in Docker)
- **Staging**: 3 brokers (minimum for fault tolerance)
- **Production**: 6 brokers (3 for redundancy, headroom for growth)
- **Enterprise multi-region**: Separate Kafka clusters per region (US, EU, APAC) with MirrorMaker 2 for cross-region replication of admin events

### Replication Factor
- Standard topics: RF=3 (survives loss of 2 brokers)
- Audit topics: RF=3 with `min.insync.replicas=2` (write acknowledged only when 2 replicas have the message)

### Log Retention
| Topic Category | Retention | Rationale |
|---|---|---|
| CRM entity events | 7 days | Sufficient for consumer lag recovery |
| Audit events | 30 days | Compliance + replay buffer |
| Tenant lifecycle | 90 days | Slow events, critical for recovery |
| GDPR erasure | 90 days | Must complete erasure even after delays |
| Notifications | 3 days | Not worth replaying old notifications |
| Webhook delivery | 7 days | Retry window for failed deliveries |

---

## Topic Architecture

### Topic Naming Convention
`{environment}.{domain}.{entity}.{event_verb}`

Production examples:
```
prod.crm.contact.created
prod.crm.contact.updated
prod.crm.contact.deleted
prod.crm.deal.created
prod.crm.deal.stage_changed
prod.crm.deal.won
prod.crm.deal.lost
prod.crm.account.updated
prod.crm.activity.created
prod.tenant.user.invited
prod.tenant.user.suspended
prod.tenant.plan.changed
prod.tenant.suspended
prod.workflow.triggered
prod.workflow.action_failed
prod.gdpr.erasure_requested
prod.gdpr.erasure_completed
prod.audit.event.written
```

### Topic Partitioning

All CRM entity topics partitioned by `tenant_id`:
- Guarantees all events for a single tenant are ordered within their partition
- Consumer groups can scale up to `partition_count` consumers (each consumer handles a subset of tenants)
- Hot tenant isolation: a large tenant's events fill their specific partition(s) without affecting others

**Partition count selection**:
- Start with 32 partitions for high-volume topics (`crm.contact.*`, `crm.deal.*`)
- 16 partitions for medium-volume topics
- 4 partitions for low-volume topics (tenant lifecycle, GDPR)
- Rule: partition count >= max expected consumer instances × 2 (allows scale-up without rebalancing)

---

## Producer Design

### Producer Configuration

| Setting | Value | Rationale |
|---|---|---|
| `acks` | `all` | Wait for all in-sync replicas to acknowledge before confirming |
| `retries` | `Integer.MAX_VALUE` | Retry indefinitely (combined with idempotent producer) |
| `enable.idempotence` | `true` | Exactly-once delivery at producer level |
| `max.in.flight.requests.per.connection` | `5` | Required for idempotent producer |
| `compression.type` | `lz4` | Reduces storage and network cost (CRM events have repetitive JSON structures) |
| `linger.ms` | `5` | Batch messages for 5ms before sending (improves throughput) |

### Idempotent Producer + Transactional API

For the Outbox Relay, use Kafka Transactions to achieve exactly-once semantics between consuming from the outbox and producing to Kafka:

```
BEGIN TRANSACTION
  Read pending outbox events (DB)
  Produce to Kafka (transactional producer)
  Mark events as PUBLISHED in DB
COMMIT
```

This prevents the race condition where the relay crashes after publishing to Kafka but before marking the event as PUBLISHED in the DB (which would cause duplicate publishing on recovery).

---

## Consumer Group Design

### Consumer Group Responsibilities

| Consumer Group | Topics Consumed | Action | Scaling |
|---|---|---|---|
| `audit-writer` | All `crm.*`, `tenant.*` | Write to audit_log table | 4 consumers (32 partitions / 4 = 8 partitions each) |
| `es-indexer` | `crm.contact.*`, `crm.deal.*`, `crm.account.*` | Upsert ES documents | 8 consumers (keep up with write rate) |
| `workflow-engine` | All `crm.*` | Evaluate workflow triggers | 8 consumers (CPU intensive) |
| `notification-dispatcher` | `crm.deal.*`, `crm.activity.*`, `tenant.user.*`, `workflow.*` | Send emails/push notifications | 4 consumers |
| `webhook-dispatcher` | All `crm.*` | Deliver to tenant webhook endpoints | 8 consumers (I/O bound: HTTP calls) |
| `cache-invalidator` | All `crm.*` | Invalidate Redis cache keys | 2 consumers (lightweight) |
| `gdpr-orchestrator` | `gdpr.erasure_requested` | Coordinate erasure across systems | 1 consumer (low volume, sequential) |

### Consumer Configuration

| Setting | Value | Rationale |
|---|---|---|
| `enable.auto.commit` | `false` | Manual offset commit after successful processing |
| `auto.offset.reset` | `earliest` | On new consumer group startup, process from beginning |
| `max.poll.records` | `100` | Limit records per poll to prevent processing timeout |
| `max.poll.interval.ms` | `30000` | Consumer must poll within 30s or be considered dead |
| `isolation.level` | `read_committed` | Only read messages from committed transactions |

### Consumer Offset Management

Offsets are committed only AFTER successful processing:
1. Fetch batch of records from Kafka
2. Process each record (write to DB, call API, etc.)
3. On success: `consumer.commitSync(offsets)` — advance the offset
4. On failure: do NOT commit offset — record will be reprocessed on next poll

This guarantees at-least-once processing: every message is processed at least once. Combined with idempotent consumers, this achieves effectively-exactly-once semantics.

---

## Idempotent Consumer Patterns

### Pattern 1: Database Deduplication Key

```
Before processing:
  SELECT 1 FROM processed_events WHERE event_id = ? AND consumer_group = ?

If not found:
  Process the event
  INSERT INTO processed_events (event_id, consumer_group, processed_at)
  
If found:
  Skip (already processed)
```

Used by: `audit-writer`, `workflow-engine` (where duplicate processing has visible side effects)

### Pattern 2: Upsert (Natural Idempotency)

Elasticsearch upsert with `doc_as_upsert=true`: same operation regardless of whether document exists. No deduplication table needed.

Used by: `es-indexer`

### Pattern 3: Event Version Check

For state-changing consumers, use the event's `occurred_at` as an optimistic lock:
```
UPDATE contacts SET ... WHERE id = ? AND updated_at < event.occurred_at
```
If the DB row is already newer than the event, the update is a no-op.

Used by: any consumer that mutates CRM entity state

---

## Kafka Consumer Lag Monitoring

Consumer lag = number of messages produced but not yet consumed.

**Alert thresholds**:

| Consumer Group | Warning (messages behind) | Critical |
|---|---|---|
| `audit-writer` | 1,000 | 10,000 |
| `es-indexer` | 5,000 | 50,000 |
| `workflow-engine` | 500 | 5,000 |
| `webhook-dispatcher` | 1,000 | 10,000 |

**What to do on high lag**:
1. Check if the consumer is alive (health check endpoint)
2. Check if there's a processing error causing retries (check DLT)
3. Scale up consumer instances (Kubernetes HPA on consumer lag metric via KEDA)
4. If caused by a noisy tenant's bulk import: throttle that tenant's event production rate

**KEDA (Kubernetes Event-Driven Autoscaler)** scales consumer deployments based on Kafka consumer lag — the right tool for scaling consumers to match producer throughput dynamically.

---

## Dead Letter Topic Strategy

When a consumer fails to process a message after all retries:

1. Serialize the failed message + error context to the Dead Letter Topic: `{original_topic}.dlq`
2. Consumer group stops advancing past the failed message only if sequential processing is required; for independent records, skip and move on
3. DLT has 14-day retention
4. Operations dashboard allows:
   - View DLT messages by consumer group and tenant
   - Inspect failure reason
   - Replay a DLT message to the original topic after fixing the root cause
   - Discard (mark as permanently failed with admin justification)

**DLT alerts by severity**:
- `audit-writer` DLT message → High severity (PagerDuty, compliance risk)
- `es-indexer` DLT message → Medium severity (Slack alert, search staleness)
- `webhook-dispatcher` DLT message → Low severity (tenant notification only)

---

## Multi-Region Kafka Strategy (Phase 3+)

For data residency compliance (EU tenants' data must not leave EU):

- **Separate Kafka clusters per region**: EU Kafka Cluster (Frankfurt), US Kafka Cluster (Virginia), APAC Kafka Cluster (Singapore)
- Tenant routing: all events from EU-region tenants are produced to the EU Kafka cluster
- Cross-region admin events (tenant lifecycle, billing): replicated via MirrorMaker 2 with topic-level filtering (only non-PII events cross regions)
- No CRM entity events (contacts, deals) cross regional boundaries

**Tradeoff**: Each region must run its own consumer groups. Global analytics that span regions require a separate aggregation pipeline that processes anonymized/aggregated data only.

---

## Backpressure Handling

When downstream systems (PostgreSQL, Elasticsearch, external webhooks) are slow:

1. **Producer-side throttling**: Kafka producer `linger.ms` and `batch.size` naturally batch messages, reducing per-message overhead
2. **Consumer-side `max.poll.records`**: Limits how many messages a consumer fetches per poll, preventing consumer memory overflow
3. **Flow control in Webhook Dispatcher**: Use a semaphore to limit concurrent outbound HTTP requests per consumer instance (max 50 concurrent). Exceeding this pauses Kafka poll until slots free up.
4. **ES bulk indexing**: ES Indexer batches document updates (max 500 docs per bulk request, 500ms flush interval) rather than indexing one-at-a-time

---

## Interview Discussion Points

- **Why `acks=all` and not `acks=1`?** → `acks=1` means the leader broker acknowledges after writing to its own log. If the leader crashes before replicating, the message is lost. For audit events, this is unacceptable. `acks=all` ensures durability at the cost of slightly higher write latency (~2-5ms extra for replication).
- **What is the failure mode if Kafka goes completely down?** → Writes to PostgreSQL succeed (the write path does not depend on Kafka). Audit events accumulate in the `outbox_events` table. Search index lags, workflows don't trigger, webhooks don't deliver. When Kafka recovers, the Outbox Relay replays all pending events. Clients see: delayed search results, delayed notifications — but no data loss.
- **How many Kafka partitions should a topic have?** → Partitions should be >= max_consumer_instances × 2 to allow scaling. Increasing partitions after creation is possible but triggers a consumer group rebalance. Start with 32 for CRM entity topics; you can increase later. Never decrease partitions.
- **Can Kafka guarantee strict ordering across deal.created + deal.updated events?** → Yes, because both events have the same partition key (`tenant_id`). They land in the same partition and are consumed in order. If the partition key were random, ordering would not be guaranteed.
