# 10 — Message Queue Design: Payment Gateway / Wallet System

---

## Objective

Define Kafka topology for payment events — ensuring reliable, ordered, exactly-once-semantics delivery of financial events across services while maintaining the audit trail required for financial compliance.

---

## Why Kafka for Payments

| Requirement | Kafka Capability |
|---|---|
| Payment event fan-out (notification, analytics, settlement) | Multiple consumer groups |
| Reliable event delivery (no lost payment events) | Durable log, replication factor 3 |
| Payment audit trail | Immutable event log, long retention |
| Exactly-once semantics | Kafka transactions + idempotent producer |
| Settlement batch processing | Kafka streams for aggregation |
| Replay on consumer failure | Offset management, log retention |

---

## Topic Design

### Naming Convention

```
payments.{entity}.{event-type}
```

Topics:

```
payments.payment.initiated
payments.payment.authorized
payments.payment.captured
payments.payment.failed
payments.payment.refund-requested
payments.payment.refunded
payments.wallet.credited
payments.wallet.debited
payments.wallet.transfer-initiated
payments.wallet.transfer-completed
payments.settlement.batch-created
payments.settlement.completed
payments.fraud.flagged
payments.webhook.delivery-requested
payments.dead-letter.all
```

### Topic Configuration

| Topic | Partitions | Retention | Replication |
|---|---|---|---|
| payments.payment.* | 16 | 90 days | 3 |
| payments.wallet.* | 16 | 90 days | 3 |
| payments.settlement.* | 4 | 365 days | 3 |
| payments.fraud.flagged | 4 | 30 days | 3 |
| payments.webhook.delivery-requested | 8 | 3 days | 3 |
| payments.dead-letter.all | 4 | 30 days | 3 |

**90-day retention**: Financial events needed for dispute resolution (chargebacks can come 60-90 days after payment).
**365-day settlement retention**: Tax and regulatory audit requirements.

### Partition Key Strategy

| Topic | Partition Key | Reason |
|---|---|---|
| Payment events | `paymentId` | All payment lifecycle events ordered |
| Wallet events | `walletId` | Wallet state transitions in order |
| Settlement | `merchantId` | All merchant settlement events grouped |
| Webhook delivery | `merchantId` | Ordered delivery per merchant |

---

## Consumer Groups

```
payments.payment.captured
    ├── settlement-service         (accumulate for EOD settlement)
    ├── notification-service       (send receipt to payer)
    ├── merchant-service           (credit merchant ledger)
    ├── analytics-service          (revenue tracking)
    ├── fraud-service              (post-auth model scoring)
    └── audit-service              (immutable audit log writer)
```

Each consumer group is independent. Settlement failure doesn't block notification.

---

## Exactly-Once Semantics (Critical for Payments)

Payment events must be processed exactly once. "At-least-once" is not acceptable for ledger operations.

### Producer-Side Idempotency

```
enable.idempotence=true
acks=all
retries=Integer.MAX_VALUE
max.in.flight.requests.per.connection=5
```

Kafka assigns each producer a PID. Sequence numbers per partition prevent duplicate publish.

### Kafka Transactions

For atomic: write to Postgres + publish to Kafka in single transaction:

```
Kafka transaction:
  1. Begin Kafka transaction
  2. Send payment.captured event to Kafka
  3. Commit Kafka transaction
```

Combined with outbox pattern:
```
DB transaction:
  1. Update payment status to CAPTURED in payments table
  2. Write event to outbox table
  COMMIT (atomic)

Background transactional outbox publisher:
  1. Begin Kafka transaction
  2. Read from outbox table
  3. Publish to Kafka
  4. Delete from outbox table (within same Kafka transaction)
  5. Commit Kafka transaction
```

This achieves atomic exactly-once: DB update + Kafka publish are one unit.

### Consumer-Side Idempotency

Even with exactly-once producer, consumer must be idempotent (rebalance, reassignment can cause redelivery):

- Ledger debit/credit: check if entry already exists for `paymentId` before writing
- Notification: deduplication key on `{paymentId}:{eventType}` in notification service
- Merchant credit: idempotent write via `ON CONFLICT DO NOTHING` on `(payment_id, type)`

---

## Key Event Flows

### Flow 1: Wallet Transfer

```
User initiates transfer → Wallet Service
  → Debit sender: Redis DECRBY atomic
  → Persist: write to outbox
  → Emit: payments.wallet.transfer-initiated

Consumer: wallet-credit-service
  → Credit receiver: Redis INCRBY atomic
  → Persist debit + credit to Postgres ledger
  → Emit: payments.wallet.transfer-completed

Consumer: notification-service
  → Send SMS/email to sender and receiver
```

Ordering guaranteed: `transfer-initiated` always before `transfer-completed` (same partitionKey = walletId of sender).

### Flow 2: Payment Capture → Settlement

```
Payment gateway webhook: payment captured
  → Emit: payments.payment.captured

Consumer: settlement-service
  → Accumulate in settlement batch for merchant
  → EOD: emit payments.settlement.batch-created

Consumer: settlement-processor
  → Initiate bank transfer to merchant
  → Emit: payments.settlement.completed

Consumer: merchant-notification
  → Send settlement confirmation to merchant
```

### Flow 3: Refund Flow

```
User requests refund → Refund Service
  → Validate: payment eligible for refund (not already refunded, within window)
  → Emit: payments.payment.refund-requested

Consumer: payment-processor-service
  → Submit refund to card network / payment gateway
  → On success: Emit payments.payment.refunded

Consumer: wallet-service (if wallet payment)
  → Credit wallet
  
Consumer: ledger-service
  → Record refund ledger entry (debit merchant, credit payer)
  
Consumer: notification-service
  → Notify user of refund initiated
```

### Flow 4: Fraud Event

```
Fraud-detection-service scores transaction
  → Score > 80: Emit payments.fraud.flagged { paymentId, score, signals }

Consumer: payment-service
  → Reverse payment if not yet settled
  → Block account (if confirmed fraud)

Consumer: manual-review-service
  → Add to review queue

Consumer: notification-service
  → Alert user of suspicious activity
```

---

## Outbox Pattern Implementation

Payment systems must guarantee: if payment is captured in DB, the event is published to Kafka. Always.

```sql
CREATE TABLE payment_outbox (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    topic       TEXT NOT NULL,
    partition_key TEXT NOT NULL,
    payload     JSONB NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    attempts    INT DEFAULT 0
);
```

**Outbox publisher** (runs every 100ms):
1. SELECT * FROM payment_outbox WHERE published_at IS NULL AND attempts < 5 ORDER BY created_at LIMIT 100
2. For each: publish to Kafka
3. On success: UPDATE published_at = NOW()
4. On failure: UPDATE attempts = attempts + 1

Postgres transaction ensures: payment update + outbox insert are atomic.
Publisher ensures: event eventually reaches Kafka even if app restarts.

---

## Dead Letter Queue

DLQ for payment events requires special treatment:

```
Consumer fails to process payment event after 3 retries:
  → Route to payments.dead-letter.all with original topic metadata

DLQ monitoring:
  → Alert immediately on any DLQ message (payment systems must have zero tolerance)
  → Human review required: financial event failed processing
  → Manual replay after fix
```

Unlike e-commerce, payment DLQ messages are not "annoying" — they represent potential financial inconsistency. P1 alert always.

---

## Webhook Delivery Queue

Merchant webhooks are a sub-problem of message queuing:

```
payments.webhook.delivery-requested
    → webhook-delivery-service consumers (one per merchant region)
    → Retry: exponential backoff (1s, 5s, 30s, 5min, 1h)
    → After 72h without delivery: mark failed, notify merchant
    → Delivery log: stored for merchant to query via API
```

Webhook events: signed with merchant's HMAC key, so they can't be forged.

---

## Kafka Cluster Configuration

```
3 brokers across 3 AZs (MSK in AWS)
Replication factor: 3 (all topics)
min.insync.replicas: 2 (quorum writes)
unclean.leader.election.enable: false  (prefer availability loss over data loss for payments)
```

`unclean.leader.election.enable: false` — critical for payments. If all ISR replicas are down, prefer partition unavailability over electing out-of-sync leader (which could mean missed or duplicate messages).

### Producer Config (payment-grade)

```
acks=all
enable.idempotence=true
compression.type=lz4
max.block.ms=30000    # Wait up to 30s for broker availability
```

### Consumer Config

```
enable.auto.commit=false         # Manual commit after processing
isolation.level=read_committed   # Read only committed transactions (exactly-once)
max.poll.records=50              # Smaller batch for payment events (processing latency)
```

---

## Schema and Evolution

### Avro + Confluent Schema Registry

All payment events: Avro schema for type safety and evolution:

```json
{
  "type": "record",
  "name": "PaymentCaptured",
  "namespace": "com.payments.events",
  "fields": [
    {"name": "paymentId", "type": "string"},
    {"name": "merchantId", "type": "string"},
    {"name": "amount", "type": "long"},    // cents, never float for money
    {"name": "currency", "type": "string"},
    {"name": "capturedAt", "type": "string"},
    {"name": "paymentMethod", "type": "string"}
  ]
}
```

**Critical**: Use `long` (integer cents) for amounts, never `float` or `double`. Floating point for money = catastrophic precision errors.

Schema compatibility: BACKWARD_TRANSITIVE — new consumer can read all old versions.

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Exactly-once semantics | No duplicate charges | Higher latency, complexity |
| 90-day Kafka retention | Dispute resolution, audit | Storage cost (~10GB/day × 90 = 900GB) |
| Outbox pattern | Guaranteed publish | DB polling overhead |
| `unclean.leader.election=false` | No data loss | Brief unavailability if all ISR replicas down |
| P1 alert on DLQ | Immediate inconsistency detection | On-call fatigue if poorly tuned |

---

## Interview Discussion Points

- **"How do you ensure payment events are not lost?"** → Outbox pattern (DB + event atomic), Kafka replication factor 3, min ISR 2, `acks=all`
- **"How do you prevent duplicate charges from Kafka redelivery?"** → Exactly-once producer semantics + idempotent consumer (check existence before writing ledger entry)
- **"Why 90-day Kafka retention?"** → Chargebacks can arrive 60-90 days later; need to replay events for dispute resolution; also regulatory audit requirement
- **"Why `unclean.leader.election=false`?"** → Prefer unavailability over electing a lagging replica — money data loss is worse than brief downtime; explain CAP theorem tradeoff
- **"How do you handle failed webhook delivery?"** → Retry with exponential backoff up to 72h; then mark failed, notify merchant, keep delivery log queryable via API
