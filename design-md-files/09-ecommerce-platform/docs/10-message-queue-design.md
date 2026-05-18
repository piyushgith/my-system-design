# 10 — Message Queue Design: E-Commerce Platform

---

## Objective

Define Kafka topology, topic design, consumer group strategy, and event-driven patterns for decoupling e-commerce domains — order processing, inventory management, notifications, payment, and search indexing — with reliability guarantees.

---

## Why Kafka for E-Commerce

| Requirement | Kafka Capability |
|---|---|
| Order events fan-out to 5+ consumers | Topic partitioning + multiple consumer groups |
| Inventory sync after flash sale | High throughput, durable log |
| Search index sync | Event-driven indexer |
| Notification delivery | Async decoupling |
| Audit trail | Immutable event log |
| Replay on consumer failure | Log retention, offset management |

Alternatives considered:
- **RabbitMQ**: Better for task queues, not event streaming; no replay
- **SQS**: Simpler, no replay, AWS lock-in, limited throughput
- **Kafka**: Event log semantics, replay, fan-out, high throughput — correct choice

---

## Topic Design

### Naming Convention

```
{domain}.{entity}.{event-type}
```

Examples:
```
ecommerce.order.placed
ecommerce.order.status-updated
ecommerce.order.cancelled
ecommerce.inventory.reserved
ecommerce.inventory.released
ecommerce.inventory.updated
ecommerce.payment.initiated
ecommerce.payment.succeeded
ecommerce.payment.failed
ecommerce.product.created
ecommerce.product.updated
ecommerce.product.deleted
ecommerce.user.registered
ecommerce.notification.requested
ecommerce.search.index-request
```

### Topic Configuration

| Topic | Partitions | Retention | Replication |
|---|---|---|---|
| ecommerce.order.placed | 32 | 7 days | 3 |
| ecommerce.inventory.reserved | 32 | 7 days | 3 |
| ecommerce.payment.* | 16 | 30 days | 3 |
| ecommerce.product.updated | 16 | 7 days | 3 |
| ecommerce.notification.requested | 16 | 3 days | 3 |
| ecommerce.search.index-request | 8 | 1 day | 3 |
| ecommerce.dead-letter.* | 4 | 30 days | 3 |

### Partition Key Strategy

Kafka preserves order within a partition. Partition key ensures related events go to same partition:

| Topic | Partition Key | Reason |
|---|---|---|
| Order events | `orderId` | Order state transitions in order |
| Inventory events | `productId` | Inventory ops per product in order |
| Payment events | `paymentId` | Payment state machine in order |
| Product events | `productId` | Product updates in order |
| Notification | `userId` | User notification ordering |

---

## Consumer Groups

Multiple consumer groups read the same topic independently:

```
ecommerce.order.placed
    ├── notification-service        (sends confirmation email/SMS)
    ├── inventory-service           (reserve inventory)
    ├── warehouse-service           (trigger fulfillment)
    ├── analytics-service           (update metrics)
    └── fraud-detection-service     (post-order fraud check)
```

Each group tracks its own offset. Inventory service failure doesn't affect notifications.

---

## Key Event Flows

### Flow 1: Order Placement

```
User → Order API → Postgres (order: PENDING)
                 → Kafka: ecommerce.order.placed
                          ↓
              ┌───────────────────────────────┐
              │ inventory-consumer            │
              │   Reserve inventory in Redis  │
              │   Emit: inventory.reserved    │
              └───────────────────────────────┘
              ┌───────────────────────────────┐
              │ payment-consumer              │
              │   Initiate payment            │
              │   Emit: payment.initiated     │
              └───────────────────────────────┘
              ┌───────────────────────────────┐
              │ notification-consumer         │
              │   Send order confirmation     │
              └───────────────────────────────┘
```

### Flow 2: Payment Success → Order Confirmed

```
Payment Gateway Webhook → Payment Service
  → Update payment status in Postgres
  → Emit: ecommerce.payment.succeeded
          ↓
    order-service-consumer:
      Update order status → CONFIRMED
      Emit: ecommerce.order.status-updated

    warehouse-consumer:
      Trigger pick-pack-ship
```

### Flow 3: Product Update → Search Index Sync

```
Seller updates product → Product API → Postgres
  → Emit: ecommerce.product.updated
          ↓
    search-indexer-consumer:
      Read full product from DB
      Index to Elasticsearch
      (eventual consistency: 0-5s lag acceptable for search)
```

### Flow 4: Order Cancellation (Saga Compensation)

```
User cancels order → Order API
  → Emit: ecommerce.order.cancelled
          ↓
    inventory-consumer:
      Release inventory reservation (INCR Redis counter)
      
    payment-consumer:
      Initiate refund if payment captured
      
    warehouse-consumer:
      Cancel fulfillment if not shipped
      
    notification-consumer:
      Send cancellation confirmation
```

---

## Reliability Patterns

### Outbox Pattern (Critical)

Problem: Writing to DB and publishing to Kafka in same transaction is not atomic. DB commit can succeed but Kafka publish fail → inventory never reserved, payment never triggered.

Solution: Outbox pattern

```
Transaction:
  1. Write order to orders table
  2. Write event to outbox table (same DB transaction)
  → Both commit atomically, or neither

Background job:
  3. Poll outbox table for unpublished events
  4. Publish to Kafka
  5. Mark as published
```

This guarantees at-least-once delivery to Kafka.

### Idempotent Consumers

Because Kafka delivers at-least-once, consumers must be idempotent:

- Inventory reservation: check if `reservation:{orderId}` already exists in Redis before decrement
- Payment initiation: check if payment record already exists for orderId before creating
- Email notification: deduplication key in SendGrid/SES based on `orderId:event-type`

### Dead Letter Queue (DLQ)

On consumer failure after max retries:

```
ecommerce.order.placed (main topic)
    → Consumer fails 3 times
    → Message → ecommerce.dead-letter.order.placed
    → Alert fired to on-call
    → Manual review / replay tooling
```

DLQ messages retained 30 days. Replay script re-publishes to original topic after fix.

### Consumer Group Lag Monitoring

- Alert when consumer lag > 10,000 messages on critical topics (order, payment)
- Alert when lag grows faster than it's consumed (consumer falling behind)
- Dashboard: Kafka Consumer Lag in Grafana via Kafka JMX metrics

---

## Message Schema and Evolution

### Avro + Schema Registry

- All messages serialized as Avro
- Schema Registry (Confluent) enforces compatibility
- Backward-compatible schema evolution: add fields with defaults, never remove required fields
- Version controlled: `ecommerce.order.placed` schema v1, v2, v3

### Event Envelope

```json
{
  "eventId": "uuid",
  "eventType": "ecommerce.order.placed",
  "version": "1.0",
  "timestamp": "ISO-8601",
  "correlationId": "uuid",
  "sourceService": "order-service",
  "payload": { ... }
}
```

correlationId links events across services for distributed tracing.

---

## Kafka Cluster Topology

```
3 Kafka brokers (across 3 AZs)
1 ZooKeeper quorum (or KRaft mode — no ZooKeeper)
Replication factor: 3 for all critical topics
min.insync.replicas: 2 (producer waits for 2 broker acks)
```

### Producer Config

```
acks=all                    # All ISR replicas must ack
enable.idempotence=true     # Exactly-once at producer level
retries=Integer.MAX_VALUE   # Retry forever on transient failure
max.in.flight.requests.per.connection=5
```

### Consumer Config

```
enable.auto.commit=false    # Manual offset commit after processing
auto.offset.reset=earliest  # On new consumer group, start from beginning
max.poll.records=100        # Batch size per poll
```

---

## Flash Sale Event Architecture

Flash sale creates thundering herd on Kafka:

- 100,000 simultaneous requests → need controlled ingestion
- Solution: Redis-first, Kafka-second

```
Flash sale start:
  Redis: atomic check-decrement (10ms, synchronous)
    → If granted: drop event on ecommerce.flash-sale.order-granted (Kafka)
    → If rejected: return sold-out immediately (no Kafka event)

Kafka consumers (10 consumer instances):
  Process granted events at sustainable rate
  Create actual orders in Postgres
  Trigger payment
```

Kafka topic `ecommerce.flash-sale.order-granted`: 64 partitions for burst throughput.

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Outbox pattern | Guaranteed delivery | Additional table, polling overhead |
| At-least-once delivery | Reliable | Consumers must be idempotent |
| Avro + Schema Registry | Type safety, evolution | Infrastructure dependency |
| Per-domain topics | Clean boundaries | More topics to manage |
| Manual offset commit | Control | Consumer must ack after processing |

---

## Risks

| Risk | Mitigation |
|---|---|
| Kafka broker failure | Replication factor 3, min ISR 2 |
| Consumer crash mid-processing | At-least-once + idempotency |
| Schema incompatibility | Schema registry compatibility checks on deploy |
| Message ordering violation | Partition key strategy (same key = same partition) |
| DLQ overflow | Alert + PagerDuty on DLQ depth |

---

## Interview Discussion Points

- **"How do you ensure inventory reserved before payment?"** → Saga pattern: inventory reservation event consumed before payment initiation; compensation on failure
- **"What if Kafka is down?"** → Outbox pattern: events persist in DB; published when Kafka recovers
- **"How do you handle duplicate events?"** → Idempotent consumers with dedup key check before processing
- **"How many partitions for the order topic?"** → Drive from target throughput: 32 partitions × 10 MB/s/partition = enough for 320 MB/s; tune based on consumer count
- **"Kafka vs RabbitMQ for this?"** → Kafka for event log replay, fan-out to multiple consumer groups, audit trail; RabbitMQ for simple task queues without replay needs
