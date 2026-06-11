# 17 — Testing Strategy: Multi-Tenant SaaS CRM

## Objective

Define the test layers and release gates. For a multi-tenant system, the highest-stakes test target is tenant isolation: a cross-tenant leak is a company-ending bug, and the isolation test suite (Layer 3 of the defense-in-depth in 05-database-design) is a designed control, not optional coverage.

---

## Test Pyramid

| Layer | Scope | Tooling | Runs |
|---|---|---|---|
| Unit | Domain logic: pipeline transitions, workflow trigger evaluation, custom-field validation rules | JUnit 5 | Every commit |
| Integration | Repository + RLS on real PostgreSQL; Kafka outbox/consumer flows | Testcontainers (PostgreSQL + Kafka) | Every commit |
| API / E2E | Tenant-scoped journeys via REST with JWT per tenant/role | RestAssured | Every PR |
| Isolation suite | Dedicated cross-tenant attack suite (below) | Testcontainers + PgBouncer in transaction-pooling mode | Every PR — release-gating |
| Load | Noisy-tenant and bulk-import scenarios | Gatling/k6 | Nightly |

## Tenant Isolation Suite (release-gating)

This suite must run against **PgBouncer in transaction pooling mode**, not a direct connection — the `SET LOCAL` requirement (05-database-design) only shows its failure mode through a transaction pooler.

1. Create records as Tenant A; query every read endpoint as Tenant B → zero rows, every entity type
2. **Connection-reuse leak test**: interleave A and B transactions on a 1-connection PgBouncer pool; assert B never sees A's tenant context (catches session-level `SET` regressions)
3. **Missing-context test**: execute a query with no tenant variable set → zero rows (deny-by-default policy), never unfiltered rows
4. RLS coverage check: automated query of `pg_policies` — every tenant-scoped table has an enabled policy; new table without one fails CI
5. ID-guessing: Tenant B requests Tenant A's record by direct UUID → 404 (not 403 — existence must not leak)
6. Search/Elasticsearch isolation: cross-tenant search returns zero hits (index-level filter tested separately from SQL RLS)
7. Cache isolation: ABAC-aware cache keys — Tenant B request after Tenant A's identical cached query must miss

## Functional Test Priorities

- **Custom fields (dynamic schema)**: per-tenant field definitions — validation, type coercion, search, and that Tenant A's custom field never appears on Tenant B's schema
- **Workflow engine**: trigger/action matrix with loop prevention (webhook-triggers-workflow-triggers-webhook), time-based triggers under clock skew
- **RBAC/ABAC**: permission matrix tests per role; ABAC rule "rep sees own deals only" tested for both read and aggregate endpoints (aggregates leak through counts)
- **Entitlements** (18-billing-and-metering): plan limit enforcement returns `402 PLAN_LIMIT_EXCEEDED`; downgrade-at-period-end does not retroactively break existing data
- **GDPR flows**: right-to-erasure anonymizes contact across primary DB, search index, and caches — verified by querying all three

## Contract & Event Tests

- Outbox: DB commit without event publish is impossible to observe externally (relay idempotence under crash/restart)
- Webhook delivery: retries with backoff, signature verification, and tenant-misdirected webhook impossible (URL bound to tenant config)
- Consumer idempotency: replay a day of `contact.updated` events → read models unchanged

## Non-Functional Tests

- Noisy tenant: one tenant at 50× normal write rate → other tenants' P99 within SLO (validates per-tenant rate limits and Kafka partition isolation)
- Bulk import of 1M contacts → no impact on interactive latency (matches 11-failure-scenarios Scenario 9)
- RLS overhead benchmark: < 5% on indexed queries (claim made in 05-database-design — verify it stays true per release)

## CI Quality Gates

1. Isolation suite green (hard gate, no waivers)
2. Static-analysis gate: queries without `tenant_id` filter, endpoints without entitlement annotation → fail
3. Migration test: Flyway from previous release snapshot; new tenant-scoped tables have RLS policy + `(tenant_id, …)` index

## What NOT to Test

- Per-tenant UI theming/branding rendering — visual, low-risk
- Exhaustive custom-field type × entity combinations — representative matrix suffices
- Third-party SSO providers beyond one OAuth2 + one SAML reference implementation

## Ownership

Isolation suite owned by the platform team; touching auth, persistence, caching, or search requires an isolation-suite run in the PR. Feature teams own functional tests for their bounded context.
