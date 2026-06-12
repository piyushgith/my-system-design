# 18 — Governance & Ownership: URL Shortener

---

## Objective

Define who owns what, who responds when it breaks, and how decisions and changes are governed. Architecture without ownership decays; this document makes accountability explicit as the system evolves from a single-team monolith to extracted services.

---

## Module / Service Ownership

At MVP–V1 the system is a modular monolith owned by one team; ownership is per-module so boundaries are clear before extraction (see `01-high-level-architecture.md`).

| Module / Component | Owner (V1) | Owner (V2+ after extraction) | Primary SLO owned |
|---|---|---|---|
| Redirect | Core team | Redirect Service team | Redirect p99 < 50ms, 99.99% |
| URL management (create/delete/alias) | Core team | URL Management team | Create p99 < 200ms, 99.9% |
| Analytics ingestion | Core team | Data/Analytics team | Pipeline freshness < 30s |
| Identity / API keys | Core team | Identity team | Auth correctness |
| Admin / abuse | Core team | Trust & Safety | Takedown SLA |
| Infra (Redis, PostgreSQL, Kafka, CDN) | Core team | Platform/SRE | Availability of shared infra |

**Rule:** every module has exactly one owning team. No shared-ownership grey zones — ambiguous ownership is the root cause of unaddressed incidents.

---

## On-Call & Escalation

| Tier | Responsibility | Response target |
|---|---|---|
| L1 on-call (primary) | Acknowledge alert, run the runbook, mitigate | Ack < 5 min for redirect-path pages |
| L2 (secondary / module owner) | Deep diagnosis, code-level fixes | Engaged < 15 min |
| L3 (eng lead / architect) | Cross-cutting decisions, external comms | As needed |

- Redirect-path alerts (the revenue path) page immediately; analytics/admin alerts are ticket-only outside hours.
- Every alert links to a runbook **before** it is allowed to go live (see Implementation Principles in `15-implementation-roadmap.md`).
- Blameless post-incident review for any SLO breach; action items tracked to closure.

---

## SLO & Error-Budget Governance

- SLOs are defined in `00-requirements-analysis.md`; this doc assigns an accountable owner to each.
- Error budget is reviewed monthly. Budget exhausted → feature work pauses for the owning module until reliability is restored.
- The redirect SLO (99.99%) is the tightest and gates the most decisions.

---

## Change Management

| Change type | Governance |
|---|---|
| Schema change | Flyway migration, reviewed by module owner; backward-compatible by default |
| API change | Contract test must pass; breaking change requires version bump + deprecation notice |
| Architecture decision | Lightweight ADR (context, decision, consequences) committed to `/docs/adr/` |
| Infra change | Platform/SRE review; staged dev → staging → prod |

---

## Documentation Governance

- These `/docs` are the source of truth and are reviewed each quarter, and on any architecture change, by the owning team.
- A design change is not "done" until the relevant doc is updated in the same PR.
- ADRs are append-only; superseded decisions are marked, not deleted.

---

## Interview Discussion Points

- **Why assign module owners before extracting microservices?** Ownership boundaries should drive service boundaries, not the reverse — extraction is then an org-aligned refactor.
- **What happens when the error budget is exhausted?** Feature freeze for that module until reliability work restores budget — it makes reliability a first-class, funded activity.
- **Who decides an architecture change?** The owning team via an ADR, escalated to the architect only for cross-cutting impact.
