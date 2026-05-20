# 06 — Event Flow: Double-Entry Ledger Service

---

## Objective

Document the complete event flows for posting creation, reversal, balance updates, outbox relay, and downstream event consumption. Includes sequence diagrams, timing parameters, and failure paths.

---

## Event Flows Overview

| Flow | Trigger | Key Events |
|---|---|---|
| 1. Normal Posting | Caller POST /postings | PostingCreated → balance.changed |
| 2. Idempotent Repeat | Duplicate POST | No new events — returns cached response |
| 3. Posting Reversal | POST /postings/{id}/reverse | PostingReversed → balance.changed (x2) |
| 4. Account Freeze | Admin PATCH /accounts/{id}/status | AccountFrozen |
| 5. Outbox Relay | Background scheduler (every 500ms) | outbox → Kafka |
| 6. Balance Cache Refresh | Post-posting invalidation | Async snapshot + cache update |
| 7. Reconciliation Run | Admin trigger | ReconciliationCompleted, DiscrepancyFound |

---

## Flow 1: Normal Posting

```mermaid
sequenceDiagram
    participant CALLER as Payment Service
    participant GW as API Gateway
    participant API as Ledger API
    participant IDP as Idempotency Guard
    participant POST as Posting Module
    participant DB as PostgreSQL (Primary)
    participant SNAP as Snapshot Manager
    participant REDIS as Redis
    participant OUTBOX as Outbox Relay
    participant KAFKA as Kafka

    CALLER->>GW: POST /postings {idempotency_key, legs}
    GW->>API: forward (auth verified, rate check passed)

    API->>IDP: checkIdempotency(key)
    IDP->>REDIS: GET idempotency:{key}
    REDIS-->>IDP: nil (not found)

    IDP->>POST: proceed with posting

    POST->>POST: validateDebitCreditBalance(legs)
    POST->>DB: SELECT account status for all leg accounts (batched)
    DB-->>POST: all accounts ACTIVE

    POST->>DB: BEGIN TRANSACTION
    POST->>DB: INSERT INTO postings (...)
    POST->>DB: INSERT INTO journal_entries (all legs, batch insert)
    POST->>DB: UPDATE account_snapshots (CAS for each account)
    POST->>DB: INSERT INTO outbox_events (posting.completed payload)
    POST->>DB: COMMIT

    DB-->>POST: commit success (all or nothing)

    POST->>REDIS: SET idempotency:{key} = posting_id (TTL 24h) [async]
    POST->>REDIS: INVALIDATE balance:{account_id} for each leg account [async]

    API-->>CALLER: 201 Created {posting_id, legs, balances}

    Note over OUTBOX,KAFKA: Async — runs every 500ms

    OUTBOX->>DB: SELECT FROM outbox_events WHERE published=false ORDER BY created_at LIMIT 100
    DB-->>OUTBOX: [posting.completed event]
    OUTBOX->>KAFKA: produce posting.completed (key=posting_id)
    KAFKA-->>OUTBOX: ack
    OUTBOX->>DB: UPDATE outbox_events SET published=true, published_at=now()
```

**Timing Budget:**
| Step | Target Duration |
|---|---|
| Idempotency Redis check | < 2ms |
| Account status DB read | < 5ms |
| DB transaction (2-leg posting) | < 15ms |
| Redis async writes | < 2ms (fire-and-forget) |
| Total API response | < 30ms P50, < 100ms P99 |
| Outbox → Kafka | < 1 second end-to-end |

---

## Flow 2: Idempotent Repeat (Duplicate Request)

```mermaid
sequenceDiagram
    participant CALLER as Payment Service (retry)
    participant API as Ledger API
    participant IDP as Idempotency Guard
    participant REDIS as Redis

    CALLER->>API: POST /postings {idempotency_key: "payment:xyz"} (retry after timeout)
    API->>IDP: checkIdempotency("payment:xyz")
    IDP->>REDIS: GET idempotency:payment:xyz
    REDIS-->>IDP: "post_uuid_original"

    IDP->>API: return existing posting_id
    API->>DB: SELECT FROM postings WHERE posting_id = "post_uuid_original"
    DB-->>API: original posting with legs

    API-->>CALLER: 200 OK {posting_id, ...} + Header: Idempotency-Result: HIT
```

**No new DB writes, no new journal entries, no Kafka events. Pure cache-hit path.**

---

## Flow 3: Posting Reversal

```mermaid
sequenceDiagram
    participant CALLER as Payment Service
    participant API as Ledger API
    participant POST as Posting Module
    participant DB as PostgreSQL

    CALLER->>API: POST /postings/{posting_id}/reverse {idempotency_key, reason}
    API->>POST: reversePosting(postingId, idempotencyKey)

    POST->>DB: SELECT posting + all legs WHERE posting_id = ?
    DB-->>POST: original posting (status=POSTED, legs=[...])

    POST->>POST: validate original status != REVERSED (no double reversal)
    POST->>POST: construct mirror legs (flip DEBIT↔CREDIT for each leg)
    POST->>POST: validateDebitCreditBalance(mirrored legs)

    POST->>DB: BEGIN TRANSACTION
    POST->>DB: INSERT INTO postings (reversal posting, reversal_of=original_id)
    POST->>DB: INSERT INTO journal_entries (mirrored legs)
    POST->>DB: UPDATE postings SET status='REVERSED' WHERE posting_id=original_id
    POST->>DB: UPDATE account_snapshots (CAS — reverse delta for each account)
    POST->>DB: INSERT INTO outbox_events (posting.reversed)
    POST->>DB: INSERT INTO outbox_events (balance.changed for each account)
    POST->>DB: COMMIT

    POST->>REDIS: INVALIDATE balance caches for all affected accounts
    API-->>CALLER: 201 Created {reversal_posting_id, legs}
```

**Key constraint:** An already-reversed posting cannot be reversed again. Enforced by checking `status != REVERSED` before proceeding.

---

## Flow 4: Balance Cache Refresh

This flow runs asynchronously after every successful posting.

```mermaid
sequenceDiagram
    participant POST as Posting Module
    participant REDIS as Redis
    participant BAL as Balance Reader
    participant SNAP as account_snapshots (DB)

    POST->>REDIS: DEL balance:{account_id} for each affected account

    Note over BAL,SNAP: Next balance read triggers cache population

    BAL->>REDIS: GET balance:{account_id}
    REDIS-->>BAL: nil (invalidated)
    BAL->>SNAP: SELECT balance, version FROM account_snapshots WHERE account_id=?
    SNAP-->>BAL: {balance: 1250000, version: 42}
    BAL->>REDIS: SET balance:{account_id} = {balance, version} TTL 5s
    BAL-->>CALLER: return balance
```

**Trade-off:** Cache invalidation (delete on write) vs. cache update (write-through). Invalidation is chosen because:
- Snapshot update and cache update cannot be atomic (different stores)
- A brief stale window is preferable to serving a stale balance that survived a failed snapshot update
- 5-second TTL ensures maximum staleness is bounded even if invalidation is missed

---

## Flow 5: Outbox Relay (Background Job)

```mermaid
sequenceDiagram
    participant SCHEDULER as Spring @Scheduled (500ms)
    participant RELAY as OutboxRelayJob
    participant DB as PostgreSQL
    participant KAFKA as Kafka

    loop every 500ms
        RELAY->>DB: SELECT * FROM outbox_events WHERE published=false ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED
        DB-->>RELAY: [batch of unpublished events]

        loop for each event
            RELAY->>KAFKA: ProducerRecord(topic=event_type, key=posting_id, value=payload)
            KAFKA-->>RELAY: RecordMetadata (offset confirmed)
            RELAY->>DB: UPDATE outbox_events SET published=true, published_at=now() WHERE event_id=?
        end
    end
```

**`FOR UPDATE SKIP LOCKED`:** Allows multiple relay instances without double-publishing. Instance A locks row 1, instance B skips row 1 and processes row 2. Safe for horizontal scaling of the relay.

**Kafka producer config:**
- `acks=all` — all ISR replicas must acknowledge
- `enable.idempotence=true` — exactly-once producer semantics
- `retries=Integer.MAX_VALUE` — retry until delivered
- `max.in.flight.requests.per.connection=5` — ordering within partition maintained

---

## Flow 6: Downstream Consumer — Fraud Detection

```mermaid
sequenceDiagram
    participant KAFKA as Kafka (posting.completed)
    participant FRAUD as Fraud Service Consumer
    participant RULES as Rules Engine
    participant CASE as Case Management

    KAFKA->>FRAUD: posting.completed {posting_id, legs, effective_at}
    FRAUD->>FRAUD: deserialize + validate event schema version

    FRAUD->>RULES: evaluateRules(postingEvent)
    RULES->>RULES: check velocity counters, geo-anomaly, device fingerprint
    RULES-->>FRAUD: {risk_score: 85, triggered_rules: ["HIGH_VELOCITY"]}

    alt risk_score > threshold
        FRAUD->>CASE: createCase(postingId, riskScore, triggeredRules)
        FRAUD->>KAFKA: produce fraud.alert.raised event
    else
        FRAUD->>FRAUD: log clean verdict
    end

    FRAUD->>KAFKA: commit offset
```

**Consumer group:** `fraud-ledger-consumer`
- Separate consumer group per downstream domain
- Each consumer group maintains its own offset — fraud service failure does not block analytics

---

## Flow 7: Reconciliation Run

```mermaid
sequenceDiagram
    participant OPS as Finance Ops
    participant API as Recon API
    participant RECON as Reconciliation Service
    participant DB as PostgreSQL
    participant S3 as S3 (external file)
    participant NOTIFY as Notification Service

    OPS->>API: POST /reconciliation/runs {account_id, from, to, file_ref}
    API->>RECON: startReconciliation(params)
    RECON->>DB: INSERT INTO reconciliation_runs (status=PROCESSING)
    API-->>OPS: 202 Accepted {run_id}

    Note over RECON,S3: Async background processing

    RECON->>S3: fetch external statement file
    RECON->>RECON: normalize external records to canonical format
    RECON->>DB: SELECT SUM(amount) grouped by direction FROM journal_entries WHERE account_id AND effective_at BETWEEN from AND to
    DB-->>RECON: ledger totals

    RECON->>RECON: match external records against ledger entries
    Note over RECON: Matching: exact (reference_id), then fuzzy (amount + date ± 1 day)

    RECON->>DB: INSERT INTO discrepancies (unmatched records, amount differences)
    RECON->>DB: UPDATE reconciliation_runs SET status=COMPLETED, summary={...}
    RECON->>NOTIFY: notify finance team if discrepancies found
```

---

## Event Schema Catalog

| Topic | Key | Description | Consumers |
|---|---|---|---|
| `ledger.posting.completed` | posting_id | Posted successfully to journal | Fraud, Analytics, Risk, Reporting |
| `ledger.posting.reversed` | posting_id | Reversal committed | Payment, Loan (for saga compensation) |
| `ledger.balance.changed` | account_id | Balance changed after posting | Risk alerts, dashboards |
| `ledger.account.frozen` | account_id | Account frozen by admin | Payment gatekeeper |
| `ledger.account.closed` | account_id | Account closed | All upstream systems |

---

## Failure Paths

| Failure Point | Impact | Recovery |
|---|---|---|
| DB commit fails (constraint violation) | Posting not created; caller gets 4xx | Caller retries with same idempotency key |
| Redis down during idempotency check | Fall through to DB UNIQUE constraint check | Slightly slower but correct |
| Snapshot CAS fails (concurrent posting) | Retry snapshot update — up to 3 attempts | After 3 failures, log for async snapshot reconciliation |
| Outbox relay fails to produce to Kafka | Event stays unpublished; relay retries on next tick | At-least-once guaranteed; Kafka consumer must be idempotent |
| Kafka consumer falls behind | Downstream data lag; ledger unaffected | Consumer scaling, consumer group lag monitoring |

---

## Interview Discussion Points

- **Why outbox instead of direct Kafka produce in the transaction?** You cannot atomically commit to two different systems (PostgreSQL + Kafka). The outbox ensures the Kafka event is published if and only if the DB commit succeeds. Without the outbox: if the service dies after DB commit but before Kafka produce, the event is lost
- **What if the outbox relay produces to Kafka but crashes before marking published?** The event is published twice (at-least-once). Downstream consumers must be idempotent — deduplicate by `event_id` or `posting_id`
- **How does the fraud service know the posting is not reversed later?** It subscribes to `ledger.posting.reversed` and updates its case management accordingly. Fraud decisions based on a posting that was later reversed are flagged for review
- **What is the maximum delay from posting commit to Kafka delivery?** Outbox polls every 500ms. Kafka produce adds < 50ms. Total: < 600ms end-to-end. Acceptable for fraud scoring — within the fraud system's real-time processing SLA
