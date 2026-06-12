# 17 — Testing Strategy: Food Delivery Platform

---

## Objective

Verify the guarantees that define a 3-sided marketplace: a correct order **Saga with every compensation branch exercised**, reliable event publishing via the outbox, idempotent consumers, and multi-party coordination that survives partial failure. Tests validate behavior and the failure scenarios in `11-failure-scenarios.md` and the saga in `06-event-flow.md`.

---

## Test Pyramid

| Layer | Share | Scope | Tooling |
|---|---|---|---|
| Unit | ~55% | Saga state machine, compensation logic, coupon validation, ETA, fare/surge | JUnit 5 |
| Integration | ~30% | Kafka + PostgreSQL + Redis via Testcontainers; outbox poller; consumer idempotency | Testcontainers |
| Contract | ~5% | Order/Payment/Delivery service contracts (ACLs) | Pact, schema registry |
| E2E + Load | ~10% | Browse→order→pay→assign→deliver; peak-window load | k6, Gatling |

---

## The Saga Compensation Test Matrix (core of this strategy)

Every failure branch in the order saga gets a dedicated test that asserts the correct compensating transaction. This matrix is the highest-value part of the suite — saga bugs are silent and corrupt state.

| Failure point | Expected compensation | Asserted outcome |
|---|---|---|
| Payment authorization fails | Release inventory/coupon hold | Order CANCELLED, no charge, stock restored |
| Restaurant rejects order | Refund payment, release hold | Customer refunded, order CANCELLED |
| No delivery partner found | Refund or re-queue per policy | No stuck order; customer notified |
| Restaurant accepts but cancels later | Refund + partner stand-down | Consistent terminal state |
| Coupon fraud flagged | Reverse coupon, proceed or block | No double-redemption |
| Timeout at any step | Saga timeout → compensate | No order stuck in non-terminal state |

A test exists for each row; CI fails if any compensation path is unverified.

---

## What Each Layer Must Prove

### Unit
- Saga transitions: only legal moves; every non-terminal state has a timeout/compensation path.
- Coupon: single-redemption enforced; expired/invalid rejected.
- ETA and surge computed deterministically.

### Integration
- **Outbox:** DB write + outbox row atomic; poller guarantees the Kafka event even if publish initially fails (crash-recovery test).
- **Consumer idempotency:** redelivered event does not re-execute a side effect (e.g., no double charge, no double stock decrement).
- DLQ: poison message routed to DLQ with alert; does not block the partition.

### Contract
- Order↔Payment↔Restaurant↔Delivery contracts via Pact; anti-corruption-layer mappings tested.

---

## Load & Performance Testing (validates the SLOs)

Design claims: **order placement p99 < 2s**, **search p99 < 500ms**, **tracking lag < 5s**, **5M orders/day**, **10× peak in lunch/dinner windows**, **50K location RPS**.

| Scenario | Target | Pass criteria |
|---|---|---|
| Order placement (peak window) | 10× baseline | p99 < 2s; saga completes |
| Restaurant search | high | p99 < 500ms (Elasticsearch + cache) |
| Delivery location ingestion | 50K RPS | tracking lag < 5s |
| Mixed peak (lunch surge) | sustained | no saga timeouts cascading |

---

## Chaos / Failure-Injection (validates 11-failure-scenarios.md)

- **Restaurant Service outage** → browsing/paying unaffected; order saga holds state and resumes.
- **Payment gateway timeout** → saga compensates or retries idempotently; no double charge.
- **Kafka broker loss** → RF3 availability; consumers rebalance; outbox prevents lost events.
- **Partner app offline mid-trip** → tracking degrades gracefully; order state preserved.

---

## CI/CD Gates

| Stage | Gate |
|---|---|
| PR | Unit + integration pass; **every saga compensation branch covered**; changed-line coverage ≥ 80% |
| Merge | Pact contracts green; schema compatibility |
| Pre-prod | Full order E2E + peak-window load + saga-failure injection |
| Post-deploy | Synthetic order canary; saga-stuck and DLQ-depth alerts |

---

## Interview Discussion Points

- **What's the riskiest thing to leave untested in a saga?** A compensation branch — the happy path is easy; the rollback paths are where money and stock leak.
- **How do you test the outbox guarantee?** Crash the service between DB commit and Kafka publish; assert the poller still delivers the event exactly once downstream.
- **How do you test idempotent consumers?** Redeliver the same event; assert the side effect runs once.
