# 17 — Testing Strategy: Stock Trading Order Book

## Objective

Define test layers and release gates. The matching engine's correctness argument is *determinism over a durable input log* (see 10-message-queue-design, "Where Is the Durability Point?") — so the two pillars here are matching-correctness property tests and replay-determinism tests. Latency is a correctness dimension in this system and gets regression gates, not just dashboards.

---

## Test Pyramid

| Layer | Scope | Tooling | Runs |
|---|---|---|---|
| Unit / property | Matching algorithm, order book data structure, order validation | JUnit 5 + jqwik | Every commit |
| Determinism | Replay equivalence (below) | Custom harness | Every commit |
| Integration | Gateway → Disruptor → engine → Kafka → consumers | Testcontainers Kafka | Every PR |
| Recovery | Snapshot + replay crash drills | Chaos harness | Every PR (scripted) + weekly (randomized) |
| Performance | Latency/throughput regression | JMH (micro) + Gatling (system) | Nightly, gating thresholds |

## Matching Correctness (release-gating)

1. **Price-time priority invariants (property-based)**: for arbitrary generated order streams —
   - a trade never executes at a price worse than both counterparties' limits
   - no resting order at a better price is skipped (best bid/ask always matched first)
   - among equal prices, earlier arrival fills first
   - filled + remaining + cancelled quantity always equals submitted quantity (conservation)
   - book never crosses (best bid < best ask after every event)
2. **Order-type semantics matrix**: limit/market/stop-limit/IOC/FOK × partial-fill scenarios against hand-built fixtures; FOK never partially fills, IOC remainder never rests
3. **Lifecycle state machine**: NEW → PARTIALLY_FILLED → FILLED/CANCELLED/REJECTED — invalid transitions impossible; cancel/replace preserves or correctly resets time priority (replace = new priority)
4. **Self-trade and risk rejections**: pre-trade checks (buying power, position limits, duplicate detection) reject before book mutation, never after

## Determinism & Recovery (release-gating)

1. **Replay equivalence**: any generated input sequence run twice from genesis → byte-identical event output (trades, book updates, sequence numbers). This test *defines* what code may do inside the matching loop — wall-clock reads, iteration over unordered collections, or random tie-breaks fail it
2. **Snapshot equivalence**: replay from snapshot S + events > S ≡ replay from genesis — for snapshots taken at randomized points
3. **Crash drills**: kill engine at randomized points (after match, before Kafka publish; mid-snapshot) → recovery per 11-failure-scenarios regenerates identical trades; downstream consumers dedup re-published events (idempotent producer asserted)
4. **Split-brain guard**: two engines claiming one symbol — fencing prevents double-matching (Failure 8)

## Market Data & Fan-out Tests

- Sequence-number continuity per symbol; consumer gap → snapshot-resync protocol works
- **Slow-consumer policy** (07-scaling-strategy): fill a client queue → L1 conflates keep-latest, trade ticker emits `gap` marker, execution reports are never dropped, `SLOW_CONSUMER` disconnect fires at high-water timeout — each asserted separately
- Conflation correctness: conflated L1 stream's final state equals unconflated stream's final state

## Performance Gates (nightly, regression-blocking)

- JMH: matching loop latency budget per event type — p99 regression > 10% fails
- GC discipline: allocation-rate budget in the hot path (allocation profiler in CI); a PR that introduces per-event allocation in the matching loop fails even if latency looks fine on the night's run
- System: 100K orders/sec/symbol sustained (Gatling), market-data fan-out < 5ms match-to-consumer
- Recovery SLA: snapshot + replay completes < 30s at maximum tested event backlog

## CI Quality Gates

1. Property + determinism suites green (hard gates, no waivers)
2. Crash-drill suite green
3. Nightly performance within budgets
4. Kafka schema compatibility for all published topics

## What NOT to Test

- FIX protocol library internals (QuickFIX/J) — test our session config and message mapping only
- Exhaustive symbol-count scaling in CI — one symbol proves the algorithm, 10 prove isolation; 500-symbol runs belong to capacity testing, not the build
- UI/chart rendering of market data

## Ownership

Matching engine team owns property, determinism, and performance suites; the determinism harness is the review gate for any change touching the matching loop. Market-data team owns fan-out and conflation suites.
