# 17 — Testing Strategy: URL Shortener

---

## Objective

Define how every behavioral guarantee in this design is verified before and after release. A test that cannot fail when business logic changes is not a test. Each layer below maps to a specific requirement from `00-requirements-analysis.md`; the load and chaos sections exist to validate the stated SLOs, not to produce vanity numbers.

---

## Test Pyramid

| Layer | Share | Scope | Tooling |
|---|---|---|---|
| Unit | ~70% | Pure logic: Base62 encode/decode, collision-retry loop, TTL math, alias validation | JUnit 5, AssertJ |
| Integration | ~20% | Module + real dependency (PostgreSQL, Redis) via Testcontainers | Testcontainers, Spring Boot Test |
| Contract | ~5% | API request/response shape, error envelope, pagination | Spring Cloud Contract / Pact |
| E2E + Load | ~5% | Full redirect path through cache; SLO validation | k6 / Gatling, Playwright |

Anti-pattern to avoid: testing that a repository calls `save()` (implementation detail). Test instead that a created short code is retrievable and resolves to the original URL (behavior).

---

## What Each Layer Must Prove

### Unit
- Base62 encoding is reversible and collision-free for distinct inputs.
- Collision-retry generates a new code and never returns a duplicate; retry count is bounded.
- TTL expiry boundary: a URL at `expires_at - 1s` resolves, at `expires_at + 1s` returns 410/404.
- Anonymous vs owned URL authorization rules.

### Integration
- Create → persist → read-back through PostgreSQL returns identical long URL.
- Redis cache-aside: miss populates cache; second read is a hit (assert no DB call via spy).
- Cache eviction on URL deletion: deleted URL is gone from both Redis and DB.
- Unique-constraint race: two concurrent inserts of the same custom alias — exactly one succeeds, the other gets a clean 409.

### Contract
- `POST /api/v1/urls` and `GET /{code}` response schemas are locked; breaking changes fail CI.
- Error envelope is consistent (code, message, correlationId).

---

## Load & Performance Testing (validates the SLOs)

The design claims **p99 redirect < 50ms at 10,000 RPS** and **100:1 read:write**. These are contractual and must be load-tested, not assumed.

| Scenario | Target | Pass criteria |
|---|---|---|
| Redirect hot path (cache hit) | 10K RPS sustained | p99 < 50ms, error rate < 0.1% |
| Redirect cache miss (cold) | 1K RPS | p99 < 150ms (DB read-replica path) |
| URL creation | 300 RPS | p99 < 200ms |
| Hot-key (single viral URL) | 5K RPS to one code | No Redis node saturation; Caffeine L0 absorbs |

Run load tests against a staging environment that mirrors prod cache topology. Gate releases on p99 regression > 10% vs the last green run.

---

## Chaos / Failure-Injection (validates 11-failure-scenarios.md)

Each documented failure scenario gets a chaos experiment:

- **Redis down** → redirect path falls back to read replica; assert functional at higher latency, not an outage.
- **PostgreSQL primary failover** → measure actual RTO against the < 5 min claim.
- **Kafka consumer lag spike** → click events buffer; no redirect-path impact; DLQ catches poison events.
- **Cache stampede on a viral URL** → single-flight / request coalescing prevents DB pile-on.

Run as monthly game days from V2 onward.

---

## Test Data & Environments

- Deterministic seed data via Flyway test migrations; no shared mutable fixtures.
- Each integration test owns its data and cleans up (or uses a fresh Testcontainer).
- Synthetic key space for collision testing (force the generator into a small space to exercise retry).

---

## CI/CD Gates

| Stage | Gate |
|---|---|
| PR | Unit + integration must pass; coverage on changed lines ≥ 80% |
| Merge to main | Full contract suite; no breaking API diffs |
| Pre-prod | Smoke E2E + load regression check |
| Post-deploy | Synthetic redirect canary every 30s; auto-rollback on SLO breach |

---

## Interview Discussion Points

- **Why load-test the cache-hit path specifically?** The 50ms p99 is only achievable on cache hits; the test proves the cache, not the app, carries the redirect load.
- **How do you test for enumeration resistance?** Statistical test that generated codes are uniformly distributed and non-sequential.
- **What's the cheapest test that catches the most regressions?** The create→read-back integration test — it exercises key-gen, persistence, and cache in one path.
