# Shared Pattern: Idempotency and Transactional Outbox

## Objective

Seven projects in this portfolio (banking core, CRM, ledger, KYC, credit scoring, order book, loan servicing) each restate idempotency and outbox designs with small wording differences. This document is the canonical version. Project docs should describe only their *deviations* from this pattern, not re-derive it. Divergence between restated copies is how the portfolio's internal contradictions happened (see the money-type and durability fixes in projects 11 and 24).

---

## Pattern 1: Idempotent Request Handling

### Key design

- **Caller supplies the key**: `Idempotency-Key` header (HTTP) or field (event). The server never generates it — only the caller knows what "the same operation" means.
- **Key is namespaced and scoped**: scope = `(idempotency_key, principal)` minimum; system-to-system callers use a namespace prefix (`loan:<saga_id>:<step>`, `legacy:<source>:<id>`) so two systems cannot collide.
- **Payload fingerprint stored with the key**: SHA-256 of the canonicalized request. Same key + same fingerprint → replay the stored response. Same key + different fingerprint → `409 DUPLICATE_REQUEST` (a client bug, never silently honored).
- **In-flight handling**: key claimed (DB row INSERT or Redis SETNX) before processing; a concurrent duplicate gets `409 IN_PROGRESS` with retry guidance, not a second execution.

### Storage

| Layer | Role | Failure consequence |
|---|---|---|
| DB UNIQUE constraint on key | Source of truth | None — constraint is the guarantee |
| Redis cache of key → response | Latency optimization | Cache loss is safe: falls through to DB constraint |

Redis alone is **not** an idempotency guarantee (eviction, failover). The DB constraint is the guarantee; Redis is a fast path.

### Retention

Keys retained as long as a retry is plausible: 24–72h for interactive APIs; permanently where the key is part of the financial record (ledger postings, repayment records — the key *is* the dedup fact).

## Pattern 2: Transactional Outbox

### Problem

"Write to DB and publish to Kafka" is two systems — a crash between them either loses the event or emits an event for state that rolled back. Neither is acceptable where downstream consumers act on the event.

### Canonical design

1. Business write and `INSERT INTO outbox (event_id, aggregate_id, type, payload, created_at)` happen in **one DB transaction**
2. A relay (polling publisher or Debezium CDC) reads the outbox, publishes to Kafka with `enable.idempotence=true`, marks rows published
3. Relay is **at-least-once** by design; crash between publish and mark → republish

### Consumer side (mandatory complement)

At-least-once delivery means every consumer must be idempotent:
- Dedup on `event_id` (processed-events table or upsert-by-natural-key), or
- Make the handler's effect naturally idempotent (`ON CONFLICT DO NOTHING` on a key derived from the event)

A consumer that cannot tolerate redelivery is a bug regardless of what the producer does.

### Failure handling

- Poison messages: bounded retries (3, exponential backoff) → dead-letter topic with original headers + failure cause; DLQ depth is an alerted metric, and every DLQ'd event has a triage owner
- Ordering: outbox rows published in per-aggregate order; Kafka partition key = aggregate ID, never random
- Outbox growth: published rows purged after retention (7–30 days); purge job monitored

## What Projects May Vary (and must state explicitly)

| Variation point | Example |
|---|---|
| Key scope | Banking scopes per `(key, initiated_by)`; ledger accepts caller namespaces |
| Relay mechanism | Polling (simple, seconds latency) vs Debezium CDC (low latency, more infra) — a per-project ops decision |
| Exactly-once framing | Kafka EOS/transactions only where consumer writes back to Kafka; DB-effect idempotency everywhere else |
| What is NOT outboxed | Order book hot path (10-message-queue-design in project 24): durability point is the input journal, and post-match events are derived — outbox would add latency for nothing |

## Reference Implementations in This Portfolio

- Banking core: `04-api-design` §Idempotency Design, `06-event-flow` §Transactional Outbox
- Ledger: `04-api-design` §Idempotency Design, `10-message-queue-design` §Outbox Pattern (plus "Idempotency as a Security Control" in `08-security-design`)
- Loan servicing: `10-message-queue-design` §Outbox Pattern for Financial Events; NACH double-debit prevention in `11-failure-scenarios` Failure 2 is the canonical consumer-side example
