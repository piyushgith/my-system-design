# 10 — Message Queue Design: Credit Scoring Engine

---

## Objective

Define the Kafka topic architecture, partitioning strategy, consumer group design, message schemas, and failure handling for the credit scoring event bus. Two distinct domains use Kafka: the feature pipeline (ingests upstream events to update features) and the scoring engine (publishes score events downstream).

---

## Kafka Topic Overview

| Topic | Direction | Producers | Consumers | Purpose |
|---|---|---|---|---|
| `bureau.data.refreshed` | Inbound | Bureau Integration Service | Feature Pipeline (Bureau Consumer) | New bureau report available for user |
| `transaction.posted` | Inbound | Ledger / Transaction Service | Feature Pipeline (Transaction Consumer) | New transaction for behavioral feature update |
| `bank.statement.updated` | Inbound | Account Aggregator Service | Feature Pipeline (AA Consumer) | Bank statement data for behavioral features |
| `emi.payment.received` | Inbound | Loan Performance Service | Feature Pipeline (Performance Consumer) | EMI payment for performance features |
| `credit.score.computed` | Outbound | Scoring Engine | Downstream consumers (Audit, CRM, Risk) | Every score computation event |
| `credit.score.significant_change` | Outbound | Scoring Engine | Loan Service, Risk Engine | Score changed by ≥ 20 points |
| `model.promoted` | Internal | Model Registry API | Scoring Engine pods (model loader) | New champion model — trigger hot-reload |
| `credit.audit.log` | Outbound | Scoring Engine | Audit Service, SIEM | Regulatory audit trail |
| `feature.profile.updated` | Internal | Feature Pipeline | Score Cache Invalidation Consumer | Feature store updated — invalidate score cache |

---

## Topic Configuration

### Inbound Feature Pipeline Topics

```
Topic: bureau.data.refreshed
Partitions: 20
Replication: 3
Retention: 7 days
Partition Key: user_id
acks: all
min.insync.replicas: 2
```

```
Topic: transaction.posted
Partitions: 50  (high volume — millions of transactions/day)
Replication: 3
Retention: 3 days  (feature pipeline processes fast; long retention wastes space)
Partition Key: user_id (ensures same user's transactions processed in order)
acks: all
```

**Partition key = user_id** for all feature update topics. Ensures all events for the same user land in the same partition → processed in order by the same consumer → prevents race conditions in feature updates.

### Outbound Score Topics

```
Topic: credit.score.computed
Partitions: 30
Replication: 3
Retention: 30 days
Partition Key: user_id

Topic: credit.score.significant_change
Partitions: 10  (low volume — only significant changes)
Replication: 3
Retention: 90 days  (downstream services may be slow to consume)

Topic: model.promoted
Partitions: 1   (total ordering required — one model promotion at a time)
Replication: 3
Retention: 365 days

Topic: credit.audit.log
Partitions: 50  (high volume — every admin action)
Replication: 3
Retention: 10 years (regulatory retention)
Compression: lz4
```

---

## Message Schemas (Avro)

### `bureau.data.refreshed`

```json
{
  "schema": "bureau.data.refreshed.v1",
  "user_id": "uuid",
  "refresh_id": "uuid",
  "features": {
    "bureau.cibil_score": 720,
    "bureau.dpd_last_6m": 0,
    "bureau.credit_utilization": 0.35,
    "bureau.inquiry_count_last_90d": 2,
    "bureau.account_count": 5,
    "bureau.oldest_account_months": 48,
    "bureau.total_outstanding": 250000
  },
  "bureau_as_of": "2024-01-01T00:00:00Z",
  "report_s3_path": "s3://bureau-reports/encrypted/usr_abc123/2024-01-01.json.enc",
  "triggered_by": "PERIODIC_REFRESH | APPLICATION_CONSENT | MANUAL"
}
```

**No PAN or Aadhaar in Kafka message.** `report_s3_path` is a pointer to the encrypted raw report (for compliance audit). Feature values are numerical aggregates — not PII.

### `credit.score.computed`

```json
{
  "schema": "credit.score.computed.v1",
  "event_id": "uuid",
  "request_id": "uuid",
  "user_id": "uuid",
  "score": 750,
  "score_band": "EXCELLENT",
  "model_version": "xgb-v2.3.1",
  "model_role": "CHAMPION",
  "product_type": "PERSONAL_LOAN",
  "source": "REAL_TIME",
  "is_thin_file": false,
  "reason_codes": ["03", "02"],
  "computed_at": "2024-01-15T10:30:00Z"
}
```

**No `raw_pd`, no `feature_snapshot`, no `shap_values` in the event.** These are internal model data — not published in the message bus. Downstream services receive the consumer-facing score only.

### `credit.score.significant_change`

```json
{
  "schema": "credit.score.significant_change.v1",
  "event_id": "uuid",
  "user_id": "uuid",
  "old_score": 780,
  "new_score": 720,
  "delta": -60,
  "direction": "NEGATIVE",
  "model_version": "xgb-v2.3.1",
  "computed_at": "2024-01-15T10:30:00Z",
  "product_type": "PERSONAL_LOAN"
}
```

### `model.promoted`

```json
{
  "schema": "model.promoted.v1",
  "event_id": "uuid",
  "new_champion_version": "xgb-v2.4.0",
  "retired_version": "xgb-v2.3.1",
  "product_types": ["PERSONAL_LOAN", "CREDIT_CARD"],
  "s3_model_path": "s3://models/xgb-v2.4.0.onnx",
  "model_sha256": "abc123...",
  "promoted_by": "risk-team",
  "promoted_at": "2024-01-15T09:00:00Z"
}
```

---

## Consumer Group Design

### Feature Pipeline Consumer Groups

```
Consumer Group: feature-pipeline-bureau
  Consumers: 5 pods (matches 20 partitions / 4 partitions each)
  Topic: bureau.data.refreshed
  Offset commit: manual (after Redis write confirmed)
  Processing: read bureau event → MSET bureau.* features in Redis → EXPIREAT

Consumer Group: feature-pipeline-transactions
  Consumers: 10 pods
  Topic: transaction.posted
  Offset commit: manual (after Kafka Streams state store updated)
  Processing: Kafka Streams sliding window aggregation → update behavioral features

Consumer Group: feature-pipeline-aa
  Consumers: 5 pods
  Topic: bank.statement.updated
  Offset commit: manual
  Processing: parse bank statement aggregates → update behavioral features
```

### Score Event Consumer Groups

```
Consumer Group: score-audit-writer
  Consumers: 5 pods
  Topic: credit.score.computed
  Processing: write to PostgreSQL score_history (async, non-blocking for scoring engine)

Consumer Group: score-cache-invalidator
  Consumers: 3 pods
  Topic: feature.profile.updated
  Processing: DEL score:{user_id}:* from score cache

Consumer Group: score-change-loan-service
  Consumers: Loan Service pods
  Topic: credit.score.significant_change
  Processing: Loan Service re-evaluates pending applications

Consumer Group: model-hot-reload
  Consumers: All scoring engine pods (each pod is its own consumer)
  Topic: model.promoted
  Processing: download new ONNX model from S3, hot-reload OnnxSession
```

**Critical:** `model-hot-reload` consumer group has each pod as a separate consumer instance (not a group sharing partitions). All pods must receive the model.promoted event → all pods reload independently. Pattern: each pod joins the group with a unique group.id suffix (pod name), so all receive the single-partition topic.

---

## Exactly-Once Semantics

**Feature pipeline:** at-least-once delivery + idempotent Redis writes. MSET is idempotent — re-processing the same bureau event sets the same feature values. No duplicate data risk.

**Score audit writes:** idempotency via `score_history.request_id` PRIMARY KEY. Duplicate event → INSERT conflict → caught → skip. At-least-once delivery is safe.

**Score cache invalidation:** at-least-once DEL is idempotent. DEL of already-deleted key is a no-op.

**No need for Kafka transactions (exactly-once) in this system.** All consumers are idempotent by design. Kafka transactions add overhead and complexity not warranted here.

---

## Dead Letter Queue Design

```
Topic: bureau.data.refreshed.DLQ
Topic: transaction.posted.DLQ
Topic: credit.score.computed.DLQ
```

**DLQ routing criteria:**
- Feature pipeline: message fails processing after 3 retries (e.g., Redis unavailable, malformed feature data) → sent to DLQ
- Score audit: INSERT fails after 3 retries (DB unavailable) → sent to DLQ

**DLQ processing:** DLQ consumer alerts PagerDuty. Human reviews failed messages. Ops replays DLQ to original topic after root cause resolved.

**Max retries per consumer:**
```java
@RetryableTopic(attempts = "3", backoff = @Backoff(delay = 1000, multiplier = 2))
```

Exponential backoff: 1s → 2s → 4s. After 3 failures → DLQ.

---

## Consumer Lag Monitoring

| Consumer Group | Acceptable Lag | Alert Threshold | Impact of Lag |
|---|---|---|---|
| feature-pipeline-bureau | < 60 seconds | > 300 seconds | Bureau features stale beyond 30-day refresh |
| feature-pipeline-transactions | < 60 seconds | > 300 seconds | Behavioral features not updated for recent transactions |
| score-audit-writer | < 5 minutes | > 30 minutes | score_history missing recent scores (audit gap) |
| score-cache-invalidator | < 30 seconds | > 120 seconds | Stale scores served after feature update |
| model-hot-reload | < 30 seconds | > 60 seconds | Old model serving traffic after champion promotion |

---

## Schema Evolution Strategy

Avro with Schema Registry (Confluent-compatible). All schema changes must be backward-compatible:
- Add optional fields with defaults — backward compatible
- Remove required fields — NOT allowed (breaking)
- Change field type — NOT allowed (breaking)

Model promotion topic (`model.promoted`) uses version in schema name (`model.promoted.v1`, `model.promoted.v2`). Breaking changes require new schema version + dual consumption during migration window.

---

## Interview Discussion Points

- **Why partition feature update topics by user_id?** All events for the same user must be processed in order by the same consumer. If transaction events for user A land in different partitions, consumer 1 may process EMI payment, consumer 2 may process a concurrent debit — and update `avg_monthly_credit` in race condition. user_id partitioning ensures total ordering per user within a partition
- **Why is model.promoted a single-partition topic?** Model promotion must be totally ordered. Two concurrent promotions (rare but possible) must be serialized. If two promotions land in different partitions, two different pods could load different models simultaneously without coordination. Single partition ensures all pods see promotions in the same order. Throughput: model promotions happen at most weekly — single partition is not a bottleneck
- **What happens if the score-audit-writer consumer falls behind?** Score computations succeed and are returned to callers. The score is in memory (and Redis score cache) but not yet in PostgreSQL. If the consumer lag alert fires (> 30 minutes), the scoring engine's outbox pattern kicks in: each score computation also writes to an `outbox_events` table in PostgreSQL (in the same transaction as... wait, score computation is async). Correction: score_history write is async via Kafka. If the consumer fails completely, audit gap exists. Mitigation: score cache serves as temporary buffer. Alert triggers manual investigation. For regulatory compliance: 99.9% score storage SLO (not 100% — some async failure is acceptable)
- **Why not use a single consumer group for all feature update topics?** Feature update topics have different processing logic (bureau → MSET with 32-day TTL; transactions → Kafka Streams sliding window; account aggregator → bank statement parsing). Same consumer group would require one consumer to handle all message types. Separate consumer groups allow separate scaling: transaction volume is 50× bureau refresh volume. Transaction consumers need 10 pods; bureau consumers need only 5
