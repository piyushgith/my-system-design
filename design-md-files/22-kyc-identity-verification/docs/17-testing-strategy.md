# 17 — Testing Strategy: KYC / Identity Verification Pipeline

## Objective

Define test layers and release gates. KYC's risk profile is distinctive: correctness failures are *compliance* failures (wrong state transitions, PII outliving retention, unscreened approvals), and the system's behavior depends on third-party vendors whose responses we do not control. The suite therefore centers on the state machine, the PII lifecycle, and vendor contract isolation.

---

## Test Pyramid

| Layer | Scope | Tooling | Runs |
|---|---|---|---|
| Unit | State machine transitions, tier selection rules, document validation | JUnit 5 | Every commit |
| Integration | Pipeline steps on real PostgreSQL + Kafka; encryption round-trips with a local KMS stub | Testcontainers | Every commit |
| Vendor contract | Adapter behavior against recorded/sandbox vendor responses | WireMock + vendor sandbox suites | Every PR (recorded) / nightly (live sandbox) |
| E2E | Full automated path: submit → OCR → liveness → watchlist → outcome event | Testcontainers compose | Every PR |
| Compliance | PII purge, audit trail completeness, retention enforcement | Scheduled suite | Nightly + release |

## State Machine Tests (release-gating)

1. **Exhaustive transition matrix**: every (state, event) pair — valid transitions succeed and append exactly one immutable `state_transitions` row; invalid transitions raise `InvalidTransitionException` and change nothing (matches 11-failure-scenarios Failure 2)
2. **Exactly-once step processing**: redeliver every Kafka step message twice → single transition, single vendor call recorded (idempotent consumer claim verified, and re-billing a vendor is asserted *not* to happen — see Vendor Cost Model in 00-requirements)
3. **Resumability**: kill the pipeline worker between any two steps → application resumes from persisted state, no step skipped or repeated
4. **One active application per user**: concurrent submissions → partial unique index rejects the second; terminal-state application allows a new one

## Vendor Contract Tests

- Each adapter (DigiLocker, Onfido, Jumio, LexisNexis) tested against: success, hard failure, timeout, ambiguous/low-confidence result, malformed payload — every case must map to a defined pipeline action (retry / fallback vendor / manual review), never an unhandled exception
- Circuit breaker: 5 consecutive failures opens; half-open probe closes — and "all vendors open" routes to manual review (Failure 1)
- Webhook callbacks: signature verification, replayed webhook idempotent, out-of-order webhook (result for a superseded step) ignored safely
- **Golden-set probe** (shares fixtures with 12-observability drift monitoring): fixed consented document set through each sandbox weekly; result deltas open vendor-quality tickets

## PII Lifecycle Tests (release-gating)

1. Encryption round-trip with key versioning: data written under key v1 readable after rotation to v2 (`personal_data_key_version` honored)
2. **Purge verification**: application past `pii_expires_at` → purge job nulls encrypted PII, sets `is_pii_purged`, deletes S3 objects — then *prove* it: decrypt attempts fail, S3 GET 404s, but audit/state history remains queryable
3. KMS unavailable: pipeline degrades per Failure 3 (no plaintext fallback path exists — asserted by code-path test, not policy)
4. PII never in logs/events: log and Kafka payload scanners assert no PAN/Aadhaar patterns in any emitted artifact during E2E runs

## Non-Functional Tests

- Burst: 50K applications in 4 hours (campaign profile from 07-scaling-strategy) — queue depth bounded, manual review SLA tracking continues
- Manual review queue overflow behavior (Failure 7): SLA breach alerts fire in test
- Recovery: Kafka consumer-group rebalance loop (Failure 4) chaos test

## CI Quality Gates

1. State machine matrix + PII lifecycle suites green (hard gates)
2. Vendor adapters: recorded-response suite green; nightly sandbox failures block release until triaged
3. Avro schema compatibility check against Schema Registry
4. Migration test from previous release schema snapshot

## What NOT to Test

- OCR *accuracy* in CI — that is the vendor's model, monitored in production via drift metrics (12-observability), not asserted in builds
- Real government rails (DigiLocker production) — sandbox only; production verification belongs to release smoke checks
- Exhaustive document image permutations — golden set + corruption/edge fixtures suffice

## Ownership

Pipeline team owns state machine, PII, and E2E suites. Each vendor adapter has a named owner responsible for its contract suite and sandbox credentials. Compliance team co-owns the purge verification suite (they sign off on its assertions, engineering maintains it).
