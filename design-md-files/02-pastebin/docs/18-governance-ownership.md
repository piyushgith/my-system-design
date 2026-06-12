# 18 — Governance & Ownership: Pastebin / Code Sharing Platform

---

## Objective

Define ownership, on-call accountability, and change/decision governance for the platform as it evolves from a modular monolith to selectively extracted services. Ownership is assigned per-module so boundaries are unambiguous before extraction.

---

## Module / Service Ownership

| Module / Component | Owner (V1) | Owner (after extraction) | Primary SLO owned |
|---|---|---|---|
| Paste (create/read/delete/fork) | Core team | Paste Delivery team | Read p99 < 50ms (hit); create < 500ms |
| Identity / API keys | Core team | Identity team | Auth correctness |
| Cleanup / expiry | Core team | Expiry Cleanup Service team | Cleanup lag ≤ 5 min |
| Analytics (view counts) | Core team | Analytics team | View-count accuracy < 5 min |
| Abuse / moderation | Core team | Trust & Safety | Flagged-content takedown SLA |
| Infra (PostgreSQL, Redis, S3, Kafka, CDN) | Core team | Platform/SRE | Shared-infra availability |

**Rule:** one owning team per module; no shared grey zones.

---

## On-Call & Escalation

| Tier | Responsibility | Response target |
|---|---|---|
| L1 primary | Ack, run runbook, mitigate | Ack < 10 min (read availability pages) |
| L2 module owner | Code-level diagnosis/fix | < 20 min |
| L3 eng lead | Cross-cutting + comms (e.g., DMCA) | As needed |

- Read-availability and create-latency alerts page; cleanup-backlog and analytics-lag are ticket-only.
- Every alert has a runbook before going live; blameless reviews on SLO breach.
- **DMCA / abuse escalation** has a defined legal contact and takedown SLA — it is a governance path, not just an engineering one.

---

## SLO & Error-Budget Governance

- SLOs from `00-requirements-analysis.md`; each gets an accountable owner here.
- Monthly error-budget review; exhaustion pauses feature work for the owning module.
- RPO < 1 min / RTO < 30 min are owned by Platform/SRE and validated in DR drills.

---

## Change Management

| Change type | Governance |
|---|---|
| Schema | Flyway migration, owner review, backward-compatible default |
| API | Contract test required; breaking change → version bump + deprecation |
| Architecture | ADR in `/docs/adr/` |
| Content policy / abuse rules | Trust & Safety sign-off |

---

## Documentation Governance

- `/docs` reviewed quarterly and on any architecture change by the owning team.
- Design changes update the doc in the same PR; ADRs are append-only.

---

## Interview Discussion Points

- **Why is DMCA an ownership question, not just engineering?** Takedown has legal exposure and an SLA — it needs a named owner and escalation path, not ad-hoc handling.
- **When does the Cleanup module get its own on-call?** When it is extracted to a service and its backlog SLO can page independently of the main app.
- **How do you keep docs honest?** Doc update is part of the PR's definition of done; stale docs are treated as bugs.
