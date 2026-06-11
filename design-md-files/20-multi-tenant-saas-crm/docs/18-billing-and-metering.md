# 18 — Billing, Subscription Tiers, and Usage Metering

## Objective

Design the billing and usage-metering capability for the Multi-Tenant SaaS CRM. The original document set referenced subscription tiers (Starter/Pro/Enterprise SLAs, plan-gated features) without designing how tiers are defined, enforced, metered, and invoiced. This document closes that gap. A SaaS product without a billing design has an unpriced architecture — tier limits drive rate limiting, noisy-tenant controls, and feature gating decisions made elsewhere in this suite.

---

## Design Decisions

### Build vs Buy: Use a Billing Provider, Build the Metering

| Concern | Decision | Rationale |
|---|---|---|
| Payment processing, invoicing, tax, dunning | **Buy** (Stripe Billing / Chargebee) | PCI scope, tax jurisdiction handling, and dunning workflows are undifferentiated heavy lifting |
| Usage metering and aggregation | **Build** | Usage events originate inside our domain (API calls, records, seats, workflow runs); only we can meter them correctly |
| Entitlement enforcement (plan limits) | **Build** | Must be enforced in our request path with low latency; cannot depend on a billing vendor call per request |

**When this is wrong:** a platform with usage-based pricing at massive scale (per-event billing) may need a dedicated metering pipeline (e.g., Amberflo-style) — out of scope below 100K tenants.

### Subscription Model

- **Tenant ↔ Subscription**: one active subscription per tenant; subscription references a `plan` (STARTER, PRO, ENTERPRISE) and a billing period
- **Plan = bundle of entitlements**: seat count, API requests/day, storage GB, custom fields per entity, workflow runs/month, plugin installs
- **Seats**: per-user licensing counted from active (non-deactivated) tenant users; seat changes prorated by the billing provider
- **Plan changes**: upgrade effective immediately (entitlements raised at once); downgrade effective at period end (avoids mid-period limit violations)

### Entitlements and Enforcement

Entitlements are cached per tenant (Redis, the same tenant-config cache layer defined in 09-caching-strategy) and enforced at three points:

1. **API Gateway**: per-tenant rate limits derived from plan (already designed in 04-api-design — the limit values now come from the entitlement record, not static config)
2. **Application layer**: hard limits checked on create operations (e.g., custom field count, seat invite) — return `402 PLAN_LIMIT_EXCEEDED` with the limit name and upgrade hint
3. **Async workloads**: workflow engine and bulk import check remaining monthly quota before scheduling (ties into the noisy-tenant mitigation in 06-event-flow)

**Grace, not cliff:** soft limits warn at 80% and 100%; hard enforcement starts at 110% for usage-type limits (API calls, workflow runs). Seats and storage are hard limits. Cutting a paying tenant off at exactly 100% generates support tickets that cost more than the overage.

### Usage Metering Pipeline

```
Domain events (api.request, workflow.executed, record.created, storage.scanned)
        │ existing Kafka topics — no new instrumentation path
        ▼
metering-aggregator (Kafka consumer group)
        │ tenant_id + metric + period → increment
        ▼
usage_counters (PostgreSQL, UPSERT per tenant/metric/day)
        │ nightly rollup
        ▼
billing provider usage records API (idempotent push, retry with backoff)
```

- Metering consumes the **existing** outbox-published domain events — no second instrumentation system
- `usage_counters` is the billable source of truth; Redis counters used for real-time enforcement are advisory and reconciled nightly against `usage_counters` (same reconciliation philosophy as the audit pipeline)
- Pushes to the billing provider are idempotent (period + tenant + metric as the idempotency key) — at-least-once delivery is safe

### Billing State and Tenant Lifecycle

| Billing state | Tenant effect |
|---|---|
| ACTIVE | Normal operation |
| PAST_DUE (dunning) | Banner + admin email; no functional restriction for 14 days |
| SUSPENDED | Read-only mode: logins and data export allowed, writes rejected (`402`) |
| CANCELLED | Tenant offboarding flow from 00-requirements (export window, then scheduled deletion) |

Billing webhook events (`invoice.paid`, `invoice.payment_failed`, `subscription.deleted`) arrive on a verified webhook endpoint, are persisted to an inbox table, and drive tenant state transitions through the same state machine that handles admin suspension — one suspension mechanism, two triggers.

---

## Tradeoffs

- **Provider lock-in**: Stripe/Chargebee APIs leak into the billing module only; an anti-corruption layer (`BillingProviderPort`) keeps the rest of the system provider-agnostic. Migration cost is real but contained.
- **Metering lag**: usage pushed nightly means invoices reflect day-old data. Acceptable for monthly billing; per-minute usage billing would require the heavier streaming pipeline rejected above.
- **Enforcement cache staleness**: entitlement changes propagate via cache invalidation (existing tenant-config invalidation channel); worst case a downgraded tenant briefly over-consumes. Bounded by cache TTL (60s).

## Risks

- Double-billing on metering replay — mitigated by idempotent usage push and the inbox table for webhooks
- Entitlement check missing on a new feature — mitigated by the same CI static-analysis gate philosophy used for `tenant_id` (flag new endpoints lacking an entitlement annotation)
- Revenue-impacting bugs are P1 by definition; metering reconciliation mismatch alerts page billing-owning team

## Interview Discussion Points

- Why entitlement enforcement must be in-process (latency, availability) while invoicing must be vendored (PCI, tax)
- Why downgrade-at-period-end is a correctness decision, not a UX nicety
- How metering reuses the outbox/event pipeline instead of creating a parallel instrumentation system
- What breaks first: usage counter UPSERT contention on hot tenants → same mitigation ladder as noisy-tenant handling (per-tenant Kafka partitioning, batched increments)
