# 17 — Testing Strategy: Payment Gateway / Wallet System

---

## Objective

Verify the guarantees a payment system lives or dies by: **double-entry ledger invariants (debits = credits)**, **idempotent payment APIs (no double charge)**, money-movement correctness across failures, and reconciliation integrity. In this domain, an untested compensation or idempotency path is a financial-loss bug. Tests validate behavior and the failure scenarios in `11-failure-scenarios.md`.

---

## Test Pyramid

| Layer | Share | Scope | Tooling |
|---|---|---|---|
| Unit | ~55% | Ledger entry construction, double-entry balance invariant, idempotency-key logic, fraud rules, isolation-level decisions | JUnit 5 |
| Integration | ~30% | PostgreSQL + Redis + Kafka via Testcontainers; ledger atomicity; outbox | Testcontainers |
| Contract | ~5% | Merchant API, acquirer/network adapter mocks, webhook contract | Pact, WireMock |
| E2E + Load | ~10% | Auth→capture→settle; wallet transfer; reconciliation | k6, Gatling |

---

## What Each Layer Must Prove

### Unit
- **Ledger invariant:** every transaction produces balanced double-entry rows; sum(debits) = sum(credits) for every entry. A property-based test asserts this for randomized transactions.
- **Idempotency:** a payment/refund API replayed with the same idempotency key returns the original result and charges/refunds **once**.
- Wallet balance arithmetic never goes negative for non-overdraft accounts; concurrent debits serialize correctly.
- Fraud rule engine: inline rules (< 50ms) produce correct allow/deny/review decisions.
- CVV is never persisted (assert it is absent from any stored/serialized representation).

### Integration
- Auth→capture→refund lifecycle persists balanced ledger entries atomically; partial failure rolls back cleanly.
- Wallet transfer: debit source + credit destination is atomic — **no money debited without the matching credit** (the canonical failure-2 scenario).
- Idempotency under concurrency: two simultaneous identical requests → one charge (unique constraint + Redis key asserted).
- Outbox: DB commit + event atomic; poller guarantees downstream (settlement, notification) delivery across a crash.
- Isolation levels: SERIALIZABLE on wallet-balance writes prevents write skew; READ COMMITTED + idempotency key elsewhere.

### Contract
- Acquirer/card-network and UPI adapters tested against WireMock: success, decline, timeout, duplicate-response.
- Merchant API and webhook schemas locked.

---

## Load & Performance Testing (validates the SLOs)

Design claims: **card auth p99 < 500ms (excl. bank RTT)**, **wallet transfer p99 < 200ms**, **5,000 TPS peak**, **fraud rule engine < 50ms inline**.

| Scenario | Target | Pass criteria |
|---|---|---|
| Card authorization | 5,000 TPS | p99 < 500ms (app portion); ledger stays balanced |
| Wallet transfer | high | p99 < 200ms; SERIALIZABLE contention acceptable |
| Inline fraud scoring | per request | < 50ms added latency |
| Settlement batch | EOD volume | completes in window; no online-path contention |

---

## Chaos / Failure-Injection (validates 11-failure-scenarios.md)

- **Double-charge attempt** → idempotency key + unique constraint guarantee one charge (highest-severity scenario).
- **Money debited, credit not applied** → atomicity test proves both-or-neither; recovery reconciles any gap.
- **Payment gateway timeout** → status reconciliation determines true outcome; no phantom charge.
- **Fraud service down** → fail-safe policy (block or hold per config), never silently approve high-risk.
- **PostgreSQL primary failover** → no committed ledger entry lost (RPO 0 for financial data).
- **Refund replay** → idempotency prevents double refund.
- **Settlement file mismatch** → reconciliation flags and quarantines discrepancies.

---

## CI/CD Gates

| Stage | Gate |
|---|---|
| PR | Unit + integration pass; ledger-invariant property test green; changed-line coverage ≥ 90% (financial core) |
| Merge | Acquirer/UPI contract (WireMock) + schema compatibility |
| Pre-prod | Auth→capture→settle E2E + reconciliation + double-charge injection |
| Post-deploy | Synthetic transaction canary; ledger-imbalance and reconciliation-mismatch alerts (page on any imbalance) |

---

## Interview Discussion Points

- **What's the one invariant you test above all?** Debits = credits on every ledger entry — a property-based test over randomized transactions catches the class of bugs that silently corrupts books.
- **How do you guarantee no double charge in tests?** Concurrent identical requests with the same idempotency key; assert exactly one ledger effect.
- **Why higher coverage on the financial core?** A missed branch here is direct money loss or a compliance failure, not a degraded UX.
