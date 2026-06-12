# 18 — Governance & Ownership: Ride-Sharing Platform

---

## Objective

Define service ownership, on-call accountability, and change governance for a platform where the matching path is the revenue path and several domains (location, payments) have distinct operational and compliance profiles. Ownership follows the genuine per-domain scaling differences argued in `01-high-level-architecture.md`.

---

## Service Ownership

| Service | Owning team | Primary SLO / responsibility |
|---|---|---|
| Location Service | Maps/Location team | 250K writes/sec; ingestion p99 < 500ms |
| Matching Service | Matching team | Match p99 < 3s (revenue path) |
| Trip Service | Trips team | Trip state-machine correctness |
| Pricing / Surge Service | Pricing team | Fare + surge correctness |
| Payment Service | Payments team | Charge correctness; **PCI scope** |
| Driver Service | Driver team | Profile, availability, docs/background checks |
| Rider Service | Rider team | Profile, history |
| Notification Service | Platform team | Push/SMS fan-out |
| Analytics Service | Data team | Metrics (batch-tolerant) |
| Redis GEO / Kafka / PostgreSQL | Platform/SRE | Shared-infra availability |
| Maps API relationship | Maps/Location team | Vendor contract + fallback (Haversine) |

---

## On-Call & Escalation

| Tier | Responsibility | Response target |
|---|---|---|
| L1 primary | Ack, runbook, mitigate | Ack < 5 min (matching/location, 99.99%) |
| L2 service owner | Code-level diagnosis | < 15 min |
| L3 eng lead + SRE | Cross-cutting / region failover | As needed |

- Matching and Location alerts page immediately (downtime = no rides); analytics is ticket-first.
- **Payments is PCI-scoped** — incidents follow a separate, audited escalation with Security/Compliance.
- Per-region operational independence (`01-high-level-architecture.md` §9): each region has on-call coverage; a region's incident stays in-region.

---

## SLO & Error-Budget Governance

- Per-service SLOs from `00-requirements-analysis.md`; matching is the tightest and gates the most decisions.
- Circuit-breaker fallbacks (stale-location cache, Haversine, deferred-charge) are owned and tested by the consuming team.
- Monthly error-budget review; exhaustion freezes the owning service's feature work.

---

## Change Management

| Change type | Governance |
|---|---|
| Matching algorithm | Matching team review + A/B/canary (1% traffic) |
| Surge/pricing rules | Pricing team review (revenue + regulatory sensitivity) |
| Payment flow | Payments team + Security review (PCI) |
| Data-residency routing | Compliance review (per-region data laws) |
| Architecture decision | ADR in `/docs/adr/` |

---

## Documentation Governance

- `/docs` reviewed quarterly and on any topology/region change.
- Runbooks for circuit-breaker fallbacks, region failover, and payment reconciliation kept current; doc update part of PR done-ness.

---

## Interview Discussion Points

- **Why is Payments governed separately?** PCI scope demands isolated audit, escalation, and change control — it cannot share the general engineering flow.
- **Who owns the Haversine fallback?** The Matching team (the consumer), because they own the degraded-mode SLO when Maps API fails.
- **How does region independence shape on-call?** Each region carries its own coverage and its incidents stay in-region — matching the data-residency and failure-isolation design.
