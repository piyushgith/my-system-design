# 17 — Testing Strategy: Banking Core System

## Objective

Define what must be tested, at which layer, and which tests gate release. For a banking core, the test suite is the primary defense of the financial invariants — a test that cannot fail when money-handling logic changes is not a test. This formalizes and extends the practices already proven in the Phase 0 MVP (see `implementation-notes.md`).

---

## Test Pyramid

| Layer | Scope | Tooling | Runs |
|---|---|---|---|
| Unit | Domain invariants, value objects (Money/paise arithmetic, account number check digits, IFSC/PAN validation) | JUnit 5 | Every commit |
| Module | Per-Modulith-module behavior with module boundaries enforced | Spring Modulith `ApplicationModules.verify()` + module slice tests | Every commit |
| Integration | Cross-module flows on real PostgreSQL (triggers, RLS, partitions, deferred constraints) | Testcontainers PostgreSQL | Every commit (H2 profile for fast local runs; Postgres-only features like the deferred double-entry trigger are *only* trusted from the Testcontainers run) |
| End-to-end | API-level lifecycle journeys (onboard → open account → deposit → transfer → statement) | MockMvc / RestAssured with role-based users | Every PR |
| Batch | EOD interest run, reconciliation jobs against seeded multi-day fixtures | Spring Batch test harness | Nightly + before release |

## Critical Invariant Tests (release-gating)

1. **Double-entry balance**: property-based test — for any generated set of postings, `sum(debits) = sum(credits)` per transaction; verified at both application layer and the Postgres deferred trigger (attempt an unbalanced insert directly; assert the DB rejects it)
2. **Idempotency under concurrency**: N parallel identical transfer requests with one idempotency key → exactly one posting; mismatch payload → `409 DUPLICATE_TRANSACTION`; in-flight → `409 IN_PROGRESS`
3. **Immutability**: UPDATE/DELETE on `journal_entries` as the application DB role must fail (privilege test, not code-path test)
4. **Available balance**: `available = current − sum(active liens)` across lien place/release/expire sequences, including concurrent lien placement (pessimistic lock test)
5. **Maker-checker state machine**: exhaustive transition matrix — every invalid transition rejected; approve-then-crash recovery (outbox event exists, consumer idempotent re-execution)
6. **Paise arithmetic**: no float anywhere in money paths — architecture test (ArchUnit rule: `double`/`float` banned in domain money types); rounding rules for interest accrual asserted against hand-computed fixtures

## Integration & Contract Tests

- Payment rail adapters (NEFT/IMPS/UPI) tested against recorded stub responses including failure/timeout/ambiguous statuses; every adapter must have an "ambiguous result → reconciliation query" test
- Kafka flows: outbox relay publishes exactly-once semantics under consumer crash/replay (Testcontainers Kafka)
- AML consumer lag scenario: events buffered, no transaction blocked (matches 11-failure-scenarios Scenario 5/8)

## Non-Functional Tests

- **Reconciliation harness**: nightly job seeds randomized transaction load, then asserts GL = sum of subledgers; any mismatch fails the nightly build — this is the test-side twin of the production reconciliation job
- Load: transfer P99 under target RPS (Gatling), with lock-contention hot-account scenario (many transfers on one account)
- Failover: kill Postgres primary mid-transfer (Testcontainers toxiproxy) → client retry with same idempotency key yields exactly one posting

## CI Quality Gates

1. All unit + module + integration green; Modulith boundary verification passes
2. Invariant suite (above) green on real PostgreSQL
3. Flyway migrations apply cleanly from baseline on a fresh DB *and* on a snapshot of the previous release's schema
4. No new ArchUnit violations (module access, float-money ban)

## What NOT to Test

- Framework behavior (Spring transaction semantics) in isolation — test *our* invariants through it
- UI snapshot tests for back-office screens — low value vs maintenance cost
- Exhaustive permutations of reference data (IFSC master) — sample + checksum validation suffices

## Ownership

Module owner writes module + invariant tests with the feature (Rule: no money-path PR merges without an invariant test that fails when the business rule is broken). Reconciliation harness owned by the platform/ledger team.
