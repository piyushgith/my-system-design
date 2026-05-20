# 10 — Message Queue Design: Loan Origination & Servicing System

## Objective

Define Kafka topic architecture, message schemas, consumer group design, and delivery guarantees for a lending platform where event ordering and at-least-once processing are critical for financial correctness.

---

## Why Kafka for a Loan System

Loan workflows span hours to days:
- Bureau check: 2-5 seconds (async callback)
- Maker-checker review: up to 24 hours
- Bank transfer confirmation: minutes to hours

Kafka enables:
- Durable async workflow coordination without tight service coupling
- Event sourcing for audit (every state transition recorded)
- Replay capability for crash recovery
- Decoupled notification, settlement, and compliance consumers

**What Kafka does NOT replace:** PostgreSQL transactions for atomic state changes within a bounded context. Kafka is between contexts; ACID transactions are within contexts.

---

## Topic Design

| Topic | Partitions | Replication | Retention | Partition Key |
|-------|-----------|-------------|-----------|--------------|
| `loan-application-events` | 20 | 3 | 30 days | applicationId |
| `underwriting-events` | 20 | 3 | 30 days | applicationId |
| `disbursement-saga-events` | 10 | 3 | 30 days | sagaId |
| `loan-account-events` | 30 | 3 | 90 days | loanAccountId |
| `emi-due-commands` | 50 | 3 | 7 days | loanAccountId |
| `emi-collection-events` | 30 | 3 | 30 days | loanAccountId |
| `nach-batch-events` | 10 | 3 | 14 days | batchDate |
| `collections-events` | 20 | 3 | 90 days | loanAccountId |
| `notification-commands` | 30 | 2 | 7 days | borrowerId |
| `audit-events` | 50 | 3 | 365 days | entityType |
| `regulatory-events` | 10 | 3 | 90 days | reportType |

---

## Partition Key Justification

**By applicationId / loanAccountId:** Ensures all events for one loan are in the same partition → in-order processing per loan → no race conditions between state transitions for the same loan.

Example of race if random partition:
- `ApplicationSubmitted` on partition 5
- `ApplicationApproved` on partition 12 (processed first!)
- Underwriting tries to approve application that's not yet submitted

Order matters: partition by entity ID prevents this class of bugs.

---

## Producer Configuration

```
acks=all                           # All ISR replicas must acknowledge
enable.idempotence=true            # Exactly-once at producer level
max.in.flight.requests.per.connection=5
linger.ms=5                        # Small batching delay
batch.size=32768                   # 32 KB batches
compression.type=snappy            # Snappy for balance of speed and compression
retries=Integer.MAX_VALUE
delivery.timeout.ms=120000         # 2 min total delivery timeout
```

**Financial events use `acks=all`:** DisbursementSaga events and LoanAccountEvents must never be silently dropped. `acks=all` ensures data durability before producer receives acknowledgment.

**Notification events use `acks=1`:** Acceptable to lose a notification (will be regenerated from loan state). Reduced latency and reduced broker load.

---

## Consumer Groups

| Consumer Group | Topics Consumed | Instances | Offset Commit |
|----------------|-----------------|-----------|--------------|
| `underwriting-service` | `loan-application-events` | 5 | Manual, after processing |
| `disbursement-service` | `loan-application-events` (offer accepted) | 3 | Manual |
| `loan-account-service` | `disbursement-saga-events` | 3 | Manual |
| `emi-scheduler` | `loan-account-events` (activated) | 3 | Manual |
| `emi-worker` | `emi-due-commands` | 50 | Manual, after NACH submit |
| `collections-service` | `emi-collection-events` | 10 | Manual |
| `notification-service` | `notification-commands` | 10 | Auto (at-most-once OK) |
| `audit-service` | All topics | 20 | Manual (at-least-once, idempotent) |
| `read-model-builder` | `loan-application-events`, `loan-account-events` | 5 | Manual |
| `regulatory-reporter` | `regulatory-events` | 3 | Manual |

---

## Consumer Configuration

```
enable.auto.commit=false          # Manual offset commit
isolation.level=read_committed    # Only read committed transactions (prevents dirty reads)
max.poll.records=100              # Small batch for financial events (careful processing)
max.poll.interval.ms=300000       # 5 min for slow external API calls (bureau, bank)
session.timeout.ms=30000
```

**`max.poll.records=100` (not 500):** Financial events require careful per-event processing. Small batch = smaller transaction scope if processing fails.

---

## Delivery Guarantee: At-Least-Once + Idempotent Consumers

All critical financial consumers are designed for at-least-once delivery + idempotent processing:

| Consumer | Idempotency Mechanism |
|----------|----------------------|
| Disbursement Service | `disbursement_sagas.idempotency_key` — check before starting saga |
| Loan Account Service | Check `loan_accounts` for existing loanAccountId from applicationId before creating |
| EMI Worker | `repayment_records.idempotency_key` — check before recording payment |
| Audit Service | `audit_log.event_id` — skip if event_id already exists |

**Why not exactly-once Kafka transactions everywhere?** Kafka transactions add latency and complexity. For most consumers, idempotent writes + at-least-once delivery achieves the same correctness guarantee with less operational overhead.

**Exception:** Kafka transactions used for `emi-worker` → `emi-collection-events` → `loan-account-events` chain, where we need atomic: "mark as deducted in NACH batch + publish collection event."

---

## Outbox Pattern for Financial Events

Problem: How to atomically update database AND publish Kafka event?

Without Outbox:
```
1. UPDATE loan_applications SET status = 'APPROVED'
2. kafka.publish(ApplicationApproved) ← crashes here → event lost
```

With Outbox Pattern:

```
BEGIN TRANSACTION;
  UPDATE loan_applications SET status = 'APPROVED';
  INSERT INTO outbox (event_type, payload, created_at) VALUES ('APPLICATION_APPROVED', {...});
COMMIT;

-- Separate process (Debezium CDC or polling):
  Read from outbox table → publish to Kafka → mark outbox row as published
```

Kafka publication is always consistent with database state. If Kafka publish fails, the outbox row remains unpublished → retry until successful.

**Implementation choice:**
- **Debezium CDC:** More complex, near-real-time (< 1 second lag). Requires Kafka Connect cluster.
- **Polling outbox:** Simpler. Scheduler polls `outbox` table every 1 second. Acceptable for loan events (1-second delay is fine — these are not real-time events).

**Decision: Polling outbox for MVP.** Debezium in V2 if polling causes issues.

---

## Dead Letter Queue Strategy

DLQ implementation pattern:

```java
@KafkaListener(topics = "loan-application-events")
public void consume(ConsumerRecord<String, String> record) {
    try {
        process(record);
        ack.acknowledge();
    } catch (RetryableException e) {
        // Will be retried by RetryableTopic (max 3 retries, backoff 5s-30s-120s)
        throw e;
    } catch (NonRetryableException e) {
        // Send to DLQ immediately (bad data, won't succeed on retry)
        dlqProducer.send("loan-application-events-dlq", record);
        ack.acknowledge();  // Commit offset — don't block pipeline for bad data
    }
}
```

Spring Kafka `@RetryableTopic` handles retry topics automatically with exponential backoff.

| DLQ Topic | SLA | Alert |
|-----------|-----|-------|
| `disbursement-saga-events-dlq` | Immediate | PagerDuty — financial |
| `emi-collection-events-dlq` | < 1 hour | PagerDuty |
| `loan-application-events-dlq` | < 4 hours | Slack |
| `notification-commands-dlq` | < 24 hours | Slack |

---

## EMI Batch Kafka Design

Monthly EMI batch uses Kafka for parallelism:

```
EMI Scheduler (CronJob, 6 AM 1st of month)
    │
    ├── Queries DB: SELECT * FROM amortization_schedule WHERE due_date = today
    │   (returns ~66,667 rows)
    │
    ├── Partitions into batches of 500
    │   (~134 messages to kafka)
    │
    └── Publishes to `emi-due-commands` (50 partitions)
        50 partitions × 50 EMI worker pods = 2.5 loans per worker per second throughput
```

Each `emi-due-commands` message:
```json
{
  "batchId": "uuid",
  "batchDate": "2024-02-01",
  "loans": [
    {
      "loanAccountId": "uuid",
      "borrowerId": "uuid",
      "nachMandateId": "mandate-ref",
      "emiAmount": "23718.45",
      "installmentNumber": 6
    }
  ]
}
```

EMI Worker:
1. Receives batch
2. Prepares NACH debit instruction file entry for each loan
3. Publishes instructions to NACH aggregation queue
4. Commits Kafka offset

NACH aggregation service collects all instructions → generates NPCI batch file → uploads SFTP.

---

## Consumer Lag Monitoring

Critical for monthly EMI batch:

```
Alert: emi-due-commands consumer lag > 5,000 messages
       → Means EMI batch is delayed → NACH submission window at risk

Alert: disbursement-saga-events lag > 100 messages
       → Loan activations delayed → borrowers waiting

Alert: Any DLQ receives messages → immediate Slack notification
Alert: disbursement DLQ → immediate PagerDuty
```

All consumer group lag exposed as Prometheus metric via Kafka consumer JMX → Grafana dashboard with 30-second refresh.
