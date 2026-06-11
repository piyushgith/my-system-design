# 17 — Testing Strategy: Double-Entry Ledger Service

## Objective

Define test layers and release gates for the financial source of truth. The ledger's correctness argument rests on a small set of invariants; the suite exists to make violating any of them impossible to ship. Property-based testing carries more weight here than in any other system in this portfolio because the invariants are algebraic.

---

## Test Pyramid

| Layer | Scope | Tooling | Runs |
|---|---|---|---|
| Unit / property | Posting validation, debit/credit direction by account type, Money arithmetic | JUnit 5 + jqwik (property-based) | Every commit |
| Integration | Posting path on real PostgreSQL: constraints, partitions, advisory locks, append-only privileges | Testcontainers PostgreSQL | Every commit |
| API | gRPC + REST contract tests, error code mapping | grpcurl-based contract suite / RestAssured | Every PR |
| Event | Outbox relay, Kafka schema compatibility, consumer replay | Testcontainers Kafka + Schema Registry compatibility check | Every PR |
| Reconciliation / soak | Randomized load then full journal-vs-snapshot audit | Custom harness | Nightly |

## Critical Invariant Tests (release-gating)

1. **Balance invariant (property-based)**: for arbitrary generated multi-leg postings (2–10 legs, mixed account types), `sum(debits) = sum(credits)` enforced; generated *unbalanced* postings always rejected with the correct error code
2. **Direction semantics**: debit increases ASSET/EXPENSE, decreases LIABILITY/EQUITY/INCOME — full account-type × entry-type matrix asserted against hand-built fixtures
3. **Idempotency races**: N concurrent identical postings, one idempotency key → exactly one journal write (DB UNIQUE + advisory lock both exercised by removing the Redis cache in the test); same key with different payload → rejected
4. **Append-only**: UPDATE/DELETE on `journal_entries` as application role fails at the privilege level; reversal is the only mutation path and produces a compensating entry, never a changed row
5. **Snapshot = journal**: after any generated posting sequence (including reversals and concurrent updates to one hot account), `account_snapshots.balance = SUM(journal_entries)` per account — run both via CAS-update path and via rebuild-from-journal, results identical
6. **Point-in-time correctness**: balance at timestamp T computed from journal equals incremental snapshot history at T, across partition boundaries
7. **Replay determinism**: replaying the journal from genesis (or any snapshot) rebuilds byte-identical balances — the event-sourcing claim in 01-high-level-architecture, tested

## Integration & Contract Tests

- Outbox: posting commit + outbox row atomic; relay crash/restart publishes exactly once observable effect (consumer-side dedup asserted)
- Kafka schema evolution: new event schema must pass Schema Registry backward-compatibility check in CI
- Downstream consumer contract: published `posting.created` event contains every field promised in 03-ddd-boundaries Published Language

## Migration & Adoption Tests (per 15-implementation-roadmap, Adoption section)

- Backfill re-runnability: run the same legacy export twice → identical ledger state (idempotency keys `legacy:<source>:<id>`)
- Unbalanced legacy record → `MIGRATION_ADJUSTMENT` leg to suspense account, never silent correction
- Parallel-run reconciliation: seeded legacy + dual-written ledger diverge by an injected bug → daily reconciliation catches it

## Non-Functional Tests

- Posting P99 < 200ms at 5,000 RPS (Gatling, single-currency pair) — per phase targets in 07-scaling-strategy
- Hot account: 1,000 concurrent postings to one account — throughput degrades gracefully, snapshot stays correct
- Failover: kill Postgres primary mid-posting → retry with same key posts exactly once (toxiproxy)

## CI Quality Gates

1. Invariant + property suites green on real PostgreSQL (H2 not acceptable for gating — advisory locks and privileges differ)
2. Nightly reconciliation soak green; any journal/snapshot mismatch is a P1 build failure
3. Schema migrations apply from previous release snapshot; partitions auto-create (pg_partman) verified

## What NOT to Test

- Re-testing PostgreSQL ACID itself — test our constraints and privileges through it
- Exhaustive currency-pair matrices — one representative non-INR currency plus the minor-unit edge cases (JPY 0, KWD 3)
- Load-testing Kafka brokers — consumer lag handling is ours; broker throughput is not

## Ownership

Ledger team owns everything in this document. External consumers own their own consumption contract tests, but the published-event contract test here is the producer-side source of truth.
