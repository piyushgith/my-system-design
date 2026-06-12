# 17 — Testing Strategy: Pastebin / Code Sharing Platform

---

## Objective

Verify every behavioral guarantee in this design before and after release, with tests that validate business behavior (the *why*), edge cases, and regression protection — not implementation details. Each layer maps to requirements in `00-requirements-analysis.md`; load and chaos sections validate the stated SLOs and the documented failure scenarios.

---

## Test Pyramid

| Layer | Share | Scope | Tooling |
|---|---|---|---|
| Unit | ~70% | Base62 key-gen, size-threshold routing (inline vs S3), expiry math, password hashing | JUnit 5, AssertJ |
| Integration | ~20% | PostgreSQL + Redis + S3 (MinIO) via Testcontainers | Testcontainers, Spring Boot Test |
| Contract | ~5% | API schema, error envelope, cursor pagination | Spring Cloud Contract / Pact |
| E2E + Load | ~5% | Create→read through CDN/cache; cleanup; SLO checks | k6 / Gatling |

---

## What Each Layer Must Prove

### Unit
- Content routing: a paste < 1 KB stays inline; ≥ 1 KB goes to S3 (assert the branch by size boundary).
- Short-key collision retry is bounded and never returns a duplicate.
- Password-protected paste: bcrypt verify succeeds for correct password, fails otherwise; password never serialized.
- Access-level enforcement: public/unlisted/private decision matrix.

### Integration
- Create large paste → content in S3, metadata in PostgreSQL, both consistent.
- Read cache-aside: miss → DB+S3 → Redis populated; second read is a pure cache hit.
- Expiry: a paste past `expires_at` returns 410 after the cleanup job processes it.
- Delete → removed from DB, S3, and Redis; CDN invalidation event emitted.

### Contract
- `POST /api/v1/pastes`, `GET /pastes/{key}`, `GET /raw/{key}` schemas locked.
- Cursor pagination contract for user paste list.

---

## Load & Performance Testing (validates the SLOs)

Design claims: **p99 read (cache hit) < 50ms**, **create < 500ms**, **80%+ cache hit on public pastes**, bursty writes.

| Scenario | Target | Pass criteria |
|---|---|---|
| Read cache hit | 400 RPS peak | p99 < 50ms |
| Read cache miss (S3) | 50 RPS | p99 < 300ms |
| Create paste (bursty) | 40 RPS peak | p99 < 500ms |
| Viral paste (thundering herd) | 1K RPS to one key | Single-flight to S3; cache hit ratio recovers |

Note: correct the documented inbound-bandwidth figure (~400 KB/s ≈ 3.2 Mbps, not Gbps) when modeling load generators.

---

## Chaos / Failure-Injection (validates 11-failure-scenarios.md)

- **PostgreSQL primary down** → reads from replica/cache continue; writes fail fast with retry guidance.
- **Redis cluster down** → cache-aside degrades to DB+S3; latency rises, no data loss.
- **S3 unavailable** → create fails cleanly (no orphan metadata); reads of small inline pastes still work.
- **Expiry cleanup backlog** → assert the system catches up and never serves expired content.
- **Short-key collision storm** → forced small key space; retry loop stays bounded.
- **Abuse/spam surge** → rate limiter sheds load; legitimate traffic unaffected.

---

## Test Data & Environments

- MinIO as S3 stand-in for local + CI; lifecycle rules tested against it.
- Flyway test migrations; each test owns and cleans its data.
- Fixtures for each access level and expiry bucket.

---

## CI/CD Gates

| Stage | Gate |
|---|---|
| PR | Unit + integration pass; changed-line coverage ≥ 80% |
| Merge | Contract suite; no breaking API diffs |
| Pre-prod | Smoke E2E (create→read→expire) + load regression |
| Post-deploy | Synthetic create/read canary; alert on SLO breach |

---

## Interview Discussion Points

- **How do you test expiry without waiting an hour?** Inject a clock or set short TTLs in test; assert cleanup-job behavior, not wall-clock time.
- **How do you verify the inline-vs-S3 boundary won't regress?** Parametrized unit test at 1023/1024/1025 bytes.
- **What protects against the viral-paste herd in tests?** Concurrency test asserting only one S3 fetch occurs for N simultaneous misses.
