# 18 — Governance & Ownership: E-Commerce Platform

---

## Objective

Define service ownership, on-call accountability, and change governance across a large domain-partitioned microservice estate. With 15 services and compliance/scaling boundaries (payments, inventory, flash sales), ownership must align to those boundaries — Conway's Law applied deliberately.

---

## Service Ownership

| Service | Owning team | Primary SLO / responsibility |
|---|---|---|
| Identity Service | Identity team | Auth, JWT/OAuth2 |
| Catalog Service | Catalog team | Product data; ES index source |
| Inventory Service | Inventory team | **No oversell**; reservation + reconciliation |
| Cart Service | Cart team | Cart p99 < 100ms |
| Order Service | Order team | Order saga; placement p99 < 500ms |
| Payment Service | Payments team | Charge/refund; ledger; **PCI scope** |
| Pricing Service | Pricing team | Price/promotion/tax correctness |
| Search Service | Discovery team | Search p99 < 300ms |
| Recommendation Service | ML team | Feed quality (degradable) |
| Notification Service | Platform team | Email/SMS/push |
| Review Service | Community team | Ratings/moderation |
| Seller Service | Marketplace team | Seller onboarding/payouts |
| Fulfillment Service | Fulfillment team | Shipping/tracking |
| Fraud Detection Service | Risk team | Pre-auth risk scoring |
| Analytics Service | Data team | Business metrics |
| Kafka / PostgreSQL / Redis / ES / S3 | Platform/SRE | Shared-infra availability |

---

## On-Call & Escalation

| Tier | Responsibility | Response target |
|---|---|---|
| L1 primary | Ack, runbook, mitigate | Ack < 5 min (checkout/payment, 99.99%) |
| L2 service owner | Code-level diagnosis | < 15 min |
| L3 eng lead + SRE | Cross-cutting / flash-sale incidents | As needed |

- Checkout, payment, and inventory alerts page immediately; catalog/search/recs are ticket-first (degradable).
- **Flash sale** is a scheduled, owned event with a pre-scale runbook and a dedicated incident commander.
- Payments follows the PCI-scoped escalation; inventory drift (Redis↔PostgreSQL) has a dedicated alert/owner.

---

## SLO & Error-Budget Governance

- Per-service SLOs from `00-requirements-analysis.md`; checkout path is tightest.
- **Inventory consistency** (no oversell, reconciliation) is owned by the Inventory team and is a correctness SLO, not just latency.
- Monthly error-budget review; exhaustion freezes the owning service's feature work.

---

## Change Management

| Change type | Governance |
|---|---|
| Inventory reservation / reconciliation logic | Inventory team review + oversell test green |
| Order saga / compensation | Order team review + compensation tests |
| Payment flow | Payments + Security (PCI) |
| Pricing / promotion rules | Pricing + Finance review |
| Flash-sale config | Order + SRE + incident-commander sign-off |
| Architecture decision | ADR in `/docs/adr/` |

---

## Documentation Governance

- `/docs` reviewed quarterly and on any topology change.
- Runbooks for flash-sale pre-scale, inventory reconciliation, and saga compensation kept current; doc update part of PR done-ness.

---

## Interview Discussion Points

- **Why align teams to domains?** Conway's Law: 15 services need ~15 owners; more services than teams produces orphaned services and unaddressed incidents.
- **Who owns "no oversell"?** The Inventory team owns it as a correctness SLO, including the Redis↔PostgreSQL reconciliation job.
- **How is a flash sale governed?** As a scheduled event with a named incident commander, pre-scale runbook, and SRE sign-off — not as routine traffic.
