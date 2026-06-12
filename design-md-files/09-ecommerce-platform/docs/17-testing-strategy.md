# 17 — Testing Strategy: E-Commerce Platform

---

## Objective

Verify the guarantees that define the platform: **no overselling under concurrency**, a correct order Saga, flash-sale correctness at extreme load, idempotent payments, and inventory consistency between Redis and PostgreSQL. Tests validate behavior and the failure scenarios in `11-failure-scenarios.md`.

---

## Test Pyramid

| Layer | Share | Scope | Tooling |
|---|---|---|---|
| Unit | ~55% | Reservation/oversell logic, optimistic-lock version handling, pricing/promotion, order state machine | JUnit 5 |
| Integration | ~30% | PostgreSQL + Redis + Kafka + ES (MinIO/S3) via Testcontainers; reservation reconciliation | Testcontainers |
| Contract | ~5% | Catalog/Order/Payment/Inventory service contracts | Pact, schema registry |
| E2E + Load | ~10% | Browse→cart→checkout→pay; flash-sale load | k6, Gatling |

---

## What Each Layer Must Prove

### Unit
- **Oversell prevention:** atomic Redis DECR returns "sold out" exactly when stock hits zero; never negative.
- Optimistic locking: concurrent inventory updates — stale `version` write is rejected and retried.
- Pricing: promotion stacking, tax, and rounding are deterministic and correct.
- Order state machine: legal transitions only; every non-terminal state has a compensation path.

### Integration
- **Reservation dual-store:** Redis reservation + PostgreSQL system-of-record stay consistent; the 30-second reconciliation job detects and corrects injected drift.
- Order placement saga: inventory reserve → fraud check → payment auth → confirm; each failure releases the reservation (assert no stock leak).
- **Outbox:** DB write + outbox atomic; poller guarantees the Kafka event across a simulated crash.
- Idempotent payment: replayed payment command charges once.

### Contract
- Catalog↔Order↔Payment↔Inventory contracts via Pact; event schemas compatibility-checked.

---

## Load & Performance Testing (validates the SLOs)

Design claims: **500K RPS sustained, 2M RPS peak (Black Friday)**, **product page p99 < 200ms**, **search p99 < 300ms**, **cart p99 < 100ms**, **order placement p99 < 500ms**.

| Scenario | Target | Pass criteria |
|---|---|---|
| Catalog browse (cache hit) | bulk of 2M peak | p99 < 200ms; CDN/Redis absorb ≥ 80% |
| Search | ~300K RPS | p99 < 300ms (Elasticsearch isolated from OLTP) |
| Order placement | ~100K RPS writes | p99 < 500ms; queue-backed, PgBouncer pooling |
| **Flash sale (oversell test)** | 10K concurrent → 100 units | exactly 100 orders succeed, rest "sold out", DB sees 100 writes |

The flash-sale oversell test is the single most important correctness-under-load test — see the 2M-RPS-peak section in `07-scaling-strategy.md`.

---

## Chaos / Failure-Injection (validates 11-failure-scenarios.md)

- **Redis (inventory) failure** → reconciliation + PostgreSQL system-of-record prevent lost reservations; oversell still blocked.
- **Payment gateway failure** → saga releases reservation; customer sees clean error; no orphan order.
- **Recommendation/review service down** → browse→cart→checkout path unaffected (graceful degradation).
- **Kafka lag** → eventual consistency window bounded; outbox prevents lost events.
- **Read-replica lag at peak** → order status tolerates staleness; checkout uses primary.

---

## CI/CD Gates

| Stage | Gate |
|---|---|
| PR | Unit + integration pass; oversell + saga-compensation covered; changed-line coverage ≥ 80% |
| Merge | Pact contracts green; schema compatibility |
| Pre-prod | Checkout E2E + flash-sale load + failure injection |
| Post-deploy | Synthetic checkout canary; inventory-drift and saga-stuck alerts |

---

## Interview Discussion Points

- **How do you test that you never oversell?** Fire 10K concurrent buys at 100 units; assert exactly 100 succeed and Redis/PostgreSQL agree after reconciliation.
- **How do you test the Redis↔PostgreSQL reconciliation?** Inject deliberate drift; assert the 30s job corrects it.
- **What's the cheapest test with the highest payoff?** The reservation-release integration test — it guards against silent stock leaks across every saga failure path.
