# 10 — Message Queue Design: KYC / Identity Verification Pipeline

---

## Objective

Define the Kafka topic design, producer configuration, consumer groups, and delivery guarantees for the KYC pipeline. Kafka is the event bus for step orchestration and outcome notification.

---

## Why Kafka for KYC

| Requirement | Kafka Feature |
|---|---|
| Async step orchestration | Topics per step; consumers trigger next step on event |
| Durable pipeline state | Kafka offset = pipeline progress checkpoint |
| Fan-out to multiple consumers | Consumer groups — Onboarding, Notification, AML each get every outcome |
| Burst absorption | Kafka as buffer during campaign spikes (50K submissions burst) |
| Replay capability | Re-process failed steps by resetting consumer offset |

**Alternative considered: Spring `@Async` + DB polling**

For 10,000 applications/day, a scheduled poller (every 30 seconds) scanning for pending steps is viable and simpler. Kafka is preferred because:
- Decouples pipeline steps from the main service
- Enables independent scaling of each step's worker
- Kafka events serve double duty: orchestration + downstream notification (one topic, multiple consumer groups)

At startup scale (< 1,000 applications/day), DB polling is acceptable. Kafka for >= 10,000.

---

## Topic Design

| Topic | Key | Partitions | Retention | Purpose |
|---|---|---|---|---|
| `kyc.application.submitted` | application_id | 12 | 7 days | Trigger pipeline start |
| `kyc.step.ocr.pending` | application_id | 12 | 1 day | Trigger OCR step |
| `kyc.step.liveness.pending` | application_id | 12 | 1 day | Trigger Liveness step |
| `kyc.step.watchlist.pending` | application_id | 12 | 1 day | Trigger Watchlist step |
| `kyc.step.completed` | application_id | 12 | 7 days | Step result broadcast |
| `kyc.manual_review.required` | application_id | 6 | 30 days | Notify review dashboard |
| `kyc.outcome.decided` | user_id | 12 | 90 days | Notify Onboarding, Notification, AML |
| `kyc.pii.expiry.scheduled` | application_id | 6 | Indefinite | Purge scheduler |

---

## Partitioning Strategy

**Partitioned by `application_id`**: Each application's events land on the same partition (deterministic hash). This ensures ordering — the pipeline processes each application's steps in sequence. Cross-partition ordering is not needed (applications are independent).

**Why not partition by `user_id`?** An application_id is the right unit of isolation. One user can have multiple applications (re-verification). Partitioning by user_id would mix events from different applications for the same user.

**12 partitions:** At 50K applications/day = ~0.58 applications/second. Each partition handles ~0.05 applications/second — trivially low. 12 partitions provide room for parallel consumers and future growth.

---

## Producer Configuration

```yaml
spring.kafka.producer:
  bootstrap-servers: kafka:9092
  acks: all
  retries: 2147483647
  enable-idempotence: true
  max-in-flight-requests-per-connection: 5
  linger-ms: 10          # Small batch window — KYC events are low-frequency
  batch-size: 4096       # 4KB (smaller batch for low-throughput topic)
  compression-type: lz4
  key-serializer: StringSerializer
  value-serializer: KafkaAvroSerializer
```

**`acks=all`:** Required — we cannot lose a step completion event. If the step completion event is lost, the pipeline stalls (next step is never triggered).

---

## Consumer Groups

### Group 1: `kyc-pipeline-orchestrator`

Consumes `kyc.application.submitted`, `kyc.step.completed` — drives the state machine.

**Logic on `kyc.application.submitted`:**
1. Create DOCUMENT_OCR step in DB
2. Produce to `kyc.step.ocr.pending`

**Logic on `kyc.step.completed` (DOCUMENT_OCR → PASS):**
1. Update step status in DB
2. Insert state transition
3. Produce to `kyc.step.liveness.pending`

### Group 2: `kyc-ocr-worker`

Consumes `kyc.step.ocr.pending`. For each event:
1. Fetch document from S3
2. Call vendor OCR API (synchronous within the consumer)
3. Produce to `kyc.step.completed` with result

**Concurrency:** 12 threads (1 per partition). Each thread handles one OCR call at a time — vendor API is the throttle.

### Group 3: `kyc-liveness-worker`

Same pattern as OCR worker, for `kyc.step.liveness.pending`.

### Group 4: `kyc-watchlist-worker`

Same pattern, for `kyc.step.watchlist.pending`. Faster (200ms per call) — handles higher concurrency.

### Group 5: `onboarding-kyc-consumer`

Consumes `kyc.outcome.decided`. On APPROVED: activates user account. On REJECTED: triggers rejection flow in Onboarding.

### Group 6: `notification-kyc-consumer`

Consumes `kyc.outcome.decided`. Sends push notification/email to user with their KYC result.

### Group 7: `aml-kyc-consumer`

Consumes `kyc.outcome.decided`. Creates customer risk profile in AML system.

---

## Exactly-Once Step Processing

The pipeline must not process the same step twice (would incur vendor cost twice and corrupt the state machine).

**Mechanism:**
1. Consumer fetches `kyc.step.ocr.pending` event (application_id, step_id)
2. Before calling vendor: check DB — `SELECT status FROM verification_steps WHERE step_id = ?`
3. If status = IN_PROGRESS or PASS: step already processed — skip (commit offset, no vendor call)
4. If status = PENDING: atomically update to IN_PROGRESS before calling vendor
5. After vendor returns: update to PASS/FAIL + commit Kafka offset

**Race condition protection:**
```sql
UPDATE verification_steps
SET status = 'IN_PROGRESS', started_at = now()
WHERE step_id = :stepId AND status = 'PENDING'
RETURNING step_id;
```

If `0 rows updated`: another consumer already started this step — skip processing.

This is optimistic locking at the consumer level — prevents duplicate vendor calls without distributed locking.

---

## Dead Letter Queue

| Step | Retry Policy | DLQ Topic | DLQ Handling |
|---|---|---|---|
| OCR worker | 3 retries, exponential backoff (1s, 5s, 30s) | `kyc.step.ocr.dlq` | Route application to MANUAL_REVIEW |
| Liveness worker | 3 retries | `kyc.step.liveness.dlq` | Route to MANUAL_REVIEW |
| Watchlist worker | 3 retries | `kyc.step.watchlist.dlq` | Route to MANUAL_REVIEW (compliance must screen manually) |
| Pipeline orchestrator | 5 retries | `kyc.orchestration.dlq` | Alert engineering on-call |

**DLQ consumer:** Polls DLQ topics every 5 minutes. Routes stuck applications to MANUAL_REVIEW with reason `SYSTEM_ERROR`. Operations team investigates root cause.

---

## Event Schema (Kafka Avro)

### `kyc.outcome.decided` event

```json
{
  "schema_version": "1.0",
  "event_id": "uuid",
  "event_type": "kyc.outcome.decided",
  "application_id": "uuid",
  "user_id": "uuid",
  "kyc_tier": "STANDARD",
  "outcome": "APPROVED",
  "decision_source": "AUTOMATED",
  "decided_at": "2024-01-15T10:33:00Z",
  "watchlist_result": {
    "hits_count": 0,
    "risk_level": "LOW"
  }
}
```

**No PII.** `user_id` is an opaque UUID — consumer must look up user details from the User Service if needed.

---

## Consumer Lag Monitoring

| Consumer Group | Alert Threshold | Impact if Lagging |
|---|---|---|
| `kyc-pipeline-orchestrator` | > 100 events | Pipeline backlog — applications stuck |
| `kyc-ocr-worker` | > 50 events | OCR bottleneck — vendor calls backing up |
| `kyc-watchlist-worker` | > 200 events | Acceptable (fast consumer) |
| `onboarding-kyc-consumer` | > 500 events | Users not being activated — revenue impact |

---

## Interview Discussion Points

- **Why separate topics for each step (kyc.step.ocr.pending, kyc.step.liveness.pending) instead of one kyc.step.pending topic?** Separate topics allow independent consumer groups per step type. The OCR worker pool can scale independently of the Watchlist worker pool. With one combined topic, fast Watchlist workers would be artificially limited by OCR's slower processing rate
- **What happens if the OCR vendor call takes 30 seconds (very slow)?** Kafka consumer `max.poll.interval.ms` defaults to 5 minutes. A 30-second vendor call is fine. If the call blocks longer than `max.poll.interval.ms`, the consumer is considered dead and partitions are rebalanced. Set `max.poll.interval.ms=600000` (10 minutes) and `max.poll.records=1` for the OCR worker to prevent this
- **How does this guarantee no step is skipped?** The state machine transition requires the previous step to be in PASS status. The pipeline orchestrator only emits `kyc.step.liveness.pending` if the step.completed event for OCR has PASS result. The DB state is the authoritative gate — Kafka events are triggers, not authorizations
- **What if the same `kyc.step.ocr.pending` event is delivered twice (Kafka at-least-once)?** The consumer checks DB before calling vendor. If step is already IN_PROGRESS or PASS: skip. UNIQUE constraint on `(application_id, step_type)` prevents duplicate step rows. Net result: exactly-once vendor calls, exactly-once state transitions — at-least-once delivery at the Kafka layer, exactly-once processing at the application layer
