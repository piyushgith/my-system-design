# 17 — Testing Strategy: Ride-Sharing Platform

---

## Objective

Verify the guarantees that define the platform: correct driver-rider matching within SLO, high-throughput location ingestion, surge correctness, and the circuit-breaker fallbacks that keep the system functional when dependencies degrade. Tests validate behavior and the failure scenarios in `11-failure-scenarios.md`.

---

## Test Pyramid

| Layer | Share | Scope | Tooling |
|---|---|---|---|
| Unit | ~60% | Driver ranking (distance + rating + acceptance), surge multiplier, trip state machine, Haversine fallback | JUnit 5 |
| Integration | ~25% | Redis GEO + Kafka + PostgreSQL via Testcontainers; matching + location pipeline | Testcontainers |
| Contract | ~5% | Trip API, location-update schema, payment-event contract | Pact, schema registry |
| E2E + Load | ~10% | Request→match→complete→pay; location ingestion throughput | k6, Gatling |

---

## What Each Layer Must Prove

### Unit
- Trip state machine: only legal transitions (CREATED→MATCHED→…→COMPLETED); illegal transitions rejected.
- Driver ranking is deterministic for a fixed input set.
- Surge multiplier computed correctly per zone; bounded to configured ceiling.
- **Haversine ETA fallback** produces a usable estimate when Maps API is unavailable.

### Integration
- Matching: `getNearbyDrivers` from Redis GEO returns drivers sorted by distance within radius; vehicle-type filter applied.
- Offer/accept: driver no-response in 15s → offer rolls to next driver (timeout path).
- Dual-write: driver location → Redis GEO (for matching) **and** Kafka (for rider tracking) without coupling.
- Trip completion → `TripCompleted` → payment saga charges with `idempotency_key = trip_id`.

### Contract
- Trip API, `driver-location-update`, and payment events locked; schema evolution checked.

---

## Load & Performance Testing (validates the SLOs)

Design claims: **matching p99 < 3s**, **location ingestion p99 < 500ms at 250K writes/sec**, **ETA p99 < 1s**, **fare estimate p99 < 2s**, **tracking update < 2s**.

| Scenario | Target | Pass criteria |
|---|---|---|
| Location ingestion | 250K writes/sec | p99 < 500ms; Redis GEO + Kafka keep up |
| Driver matching (rush hour) | bursty | p99 < 3s end-to-end |
| Live tracking fan-out | 200K concurrent riders | update lag < 2s; WebSocket fleet stable |
| Surge recalculation | per-zone | computed without blocking matching |

Location ingestion at 250K writes/sec is the headline scaling test; it must run against real Redis GEO city shards.

---

## Chaos / Failure-Injection (validates 11-failure-scenarios.md)

- **Location Service slow (>500ms p99)** → Matching opens circuit, uses last-known positions ≤ 30s; assert matches still produced.
- **Maps API slow/down** → Haversine fallback engages; matching continues at reduced ETA accuracy.
- **Payment Service degraded** → trip completes; charge deferred and retried (deferred-charge pattern); no double charge.
- **WebSocket server loss** → riders reconnect via sticky-session/pub-sub; tracking resumes.
- **Region isolation** → Mumbai traffic served entirely by India region (data residency + failure isolation).

---

## CI/CD Gates

| Stage | Gate |
|---|---|
| PR | Unit + integration pass; changed-line coverage ≥ 80% |
| Merge | Contract + schema compatibility |
| Pre-prod | Request→match→complete→pay E2E; location-ingestion soak |
| Post-deploy | Synthetic ride canary; matching-latency and ingestion-lag alerts |

---

## Interview Discussion Points

- **How do you test the circuit-breaker fallbacks?** Fault-inject latency into Location/Maps; assert the documented degraded behavior (stale cache, Haversine), not an error.
- **How do you test idempotent charging?** Replay `TripCompleted`; assert one charge via `idempotency_key = trip_id`.
- **How do you load-test 250K location writes/sec realistically?** Generate per-city GPS streams against real Redis GEO shards, not a mock.
