# 17 — Testing Strategy: Notification System

---

## Objective

Verify the delivery guarantees that define this system: at-least-once delivery, **no duplicate delivery for a repeated notification ID**, per-channel isolation, and durable retries. Tests validate business behavior and the documented failure scenarios in `11-failure-scenarios.md`, not framework wiring.

---

## Test Pyramid

| Layer | Share | Scope | Tooling |
|---|---|---|---|
| Unit | ~65% | Idempotency-key logic, quiet-hours/DND rules, template rendering, channel-selection from preferences | JUnit 5 |
| Integration | ~20% | Kafka + PostgreSQL + Redis via Testcontainers; outbox poller; consumer idempotency | Testcontainers (Kafka), Spring Boot Test |
| Contract | ~10% | Producer→API schema; **external provider mocks** (SendGrid/Twilio/FCM) | Pact, WireMock |
| E2E + Load | ~5% | End-to-end OTP path; campaign fan-out; SLO checks | k6, embedded Kafka |

---

## What Each Layer Must Prove

### Unit
- **Idempotency:** the same `notification_id` submitted twice yields exactly one dispatch (the second is a no-op).
- Quiet-hours: marketing suppressed in window; transactional (OTP) always delivered.
- DND/regulatory check (e.g., India DND) blocks SMS where required.
- Template rendering: variable substitution, SMS 160-char enforcement, missing-variable handling.
- PII masking: OTP/PII never appears in serialized logs.

### Integration
- Outbox pattern: DB write + outbox row are atomic; poller publishes exactly the committed events even across a simulated crash.
- Consumer idempotency: redelivered Kafka message does not double-send (dedup store asserted).
- Retry topology: transient provider error → retry topic → success; permanent error → DLQ.
- Preference cache: update invalidates Redis; Fanout reads fresh value.

### Contract
- Provider adapters tested against WireMock simulating success, rate-limit (429), timeout, and 5xx.
- Producer API schema locked; backward-compatible evolution enforced.

---

## Load & Performance Testing (validates the SLOs)

Design claims: **50K notifications/sec peak**, **5K/sec baseline**, **OTP p99 delivered within 5s**, **per-channel isolation**.

| Scenario | Target | Pass criteria |
|---|---|---|
| OTP transactional path | 5K/sec | p99 end-to-end < 5s |
| Campaign fan-out | 50K/sec burst | No producer blocking; consumer lag drains within window |
| **Campaign does NOT starve OTP** | mixed load | OTP p99 holds < 5s while a 50M campaign runs (priority-lane test) |
| Provider rate-limit backpressure | provider 429s | Retries respect provider limit; no retry storm |

The campaign-vs-transactional starvation test is the single most important load test — it validates the per-channel-topic and priority-lane design.

---

## Chaos / Failure-Injection (validates 11-failure-scenarios.md)

- **SendGrid outage** → failover to SES; assert no notification lost, retries durable.
- **Kafka broker loss** → RF3 keeps the topic available; consumers rebalance.
- **PostgreSQL primary failure** → outbox survives; no double-publish after recovery.
- **Retry storm** → exponential backoff + jitter prevents thundering herd on a recovering provider.
- **Bad template deploy** → render failure is contained to one template, routed to DLQ, alerted.

---

## CI/CD Gates

| Stage | Gate |
|---|---|
| PR | Unit + integration (embedded Kafka) pass; changed-line coverage ≥ 80% |
| Merge | Provider contract tests (WireMock) green; schema-registry compatibility check |
| Pre-prod | OTP E2E + campaign load + starvation test |
| Post-deploy | Synthetic OTP every 60s; DLQ-depth and consumer-lag alerts |

---

## Interview Discussion Points

- **How do you test "exactly-once user experience" on an at-least-once bus?** Assert the consumer-side dedup store collapses redeliveries — idempotency is tested at the consumer, not the broker.
- **How do you test provider failover without hitting providers?** WireMock simulates outage/429/timeout; the adapter and retry logic are the unit under test.
- **What proves campaigns won't delay OTPs?** The mixed-load starvation test with priority lanes — a pure throughput test would hide it.
