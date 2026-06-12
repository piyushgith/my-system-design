# 19 — Ownership, Governance, and Infrastructure Cost: Multi-Tenant SaaS CRM

## Objective

Name owners, decision-recording process, and indicative run cost. In a multi-tenant SaaS, cost is also a *unit-economics* question: infra cost per tenant must sit visibly below plan price, and governance must keep the tenant-isolation controls owned by someone specific.

---

## Module Ownership

| Area | Owner | On-call |
|---|---|---|
| Core CRM modules (contacts, deals, tasks) | Product engineering | Business hours + escalation |
| Tenancy, RLS, auth/RBAC/ABAC | Platform team | 24×7 — isolation incidents are P0 |
| Workflow engine, plugin runtime | Extensibility team | Business hours |
| Search (Elasticsearch sync) | Platform team | 24×7 |
| Billing & metering (doc 18) | Revenue engineering | Business hours; metering reconciliation alerts page |
| Shared infra (K8s, Kafka, Postgres, Redis) | Platform team | 24×7 |

**Isolation-control ownership is explicit:** the four defense layers in 05-database-design each have an owner — application filters (feature teams), RLS policies + PgBouncer config (platform), isolation test suite (platform, gate for auth/persistence/cache/search PRs), CI static-analysis gate (platform).

## Decision and Documentation Governance

- ADR for: any new tenant-data access path, tenancy-model changes, plan/entitlement semantics, public API contract changes
- Doc-drift rule: behavior changes covered by docs 00–18 update the doc in the same PR
- Quarterly: failure scenarios re-validated against incidents; RLS overhead benchmark re-run (the < 5% claim in 05 must stay measured, not remembered)

## Infrastructure Cost Estimate (Indicative)

Order-of-magnitude, V1–V2 scale (~1,000 tenants, RLS shared schema, single region). For relative weight, not procurement.

| Component | Sizing | ~Monthly (USD) |
|---|---|---|
| PostgreSQL (RDS Multi-AZ r6g.xlarge + 2 replicas) | | 2,500–4,000 |
| PgBouncer + EKS app nodes | 6–10 nodes | 1,200–2,000 |
| Kafka (MSK 3 brokers) | | 1,000–1,500 |
| Redis cluster | 3 nodes | 500–900 |
| Elasticsearch | 3-node cluster | 1,000–1,800 |
| CDN + API gateway | Traffic-dependent | 300–800 |
| Observability | | 600–1,200 |
| **Total** | | **~7,000–12,000/mo** |

**Unit economics check:** ~$10K/mo ÷ 1,000 tenants ≈ **$10/tenant/month infra**. Against a $50–100/seat/month Pro plan with multi-seat tenants, margin is comfortable; against a $15/mo Starter single-seat tenant it is not — Starter tiers survive on the *average* tenant being cheap (shared-schema RLS is precisely what makes the marginal tenant ~free). Track cost-per-tenant as a metric once tenant count is real; the expensive outliers are the noisy tenants already targeted by rate limits and the metering design (doc 18).

**Levers:** Elasticsearch is the first place to defer (Postgres FTS suffices below ~100 tenants — matches roadmap sequencing); read replicas added on measured replica lag, not anticipation; non-prod scheduled off-hours.

## Interview Discussion Points

- Cost-per-tenant as the SaaS architecture metric — how shared-schema RLS vs database-per-tenant is *primarily* a unit-economics decision wearing an isolation costume
- Who owns a cross-tenant leak: the layer that failed, or the team that owns isolation? (Answer: platform owns the control set; the postmortem assigns the layer)
