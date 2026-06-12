# 18 — Ownership, Governance, and Infrastructure Cost: Banking Core System

## Objective

Name who owns what, how architectural decisions are recorded, and what the system costs to run. Governance gaps are how the portfolio's doc/implementation drift happened (money-type contradiction caught in review); this document is the control against recurrence.

---

## Module Ownership

| Module (Modulith) | Owner | On-call | Notes |
|---|---|---|---|
| `account`, `ledger` | Core banking team | 24×7 rotation | Ledger changes need two reviewers, one from outside the authoring pair |
| `kyc`, `compliance` | Compliance engineering | Business hours + escalation | Compliance officer sign-off on rule changes |
| `payments` (rails) | Payments team | 24×7 rotation | Rail-specific runbooks per NEFT/RTGS/IMPS/UPI |
| `approvals` (maker-checker) | Core banking team | — | Approval matrix changes are config + audit, not code |
| `batch` (EOD) | Platform team | Pager during batch window | EOD overrun is a P1 (11-failure-scenarios Scenario 3) |
| Shared infra (K8s, Kafka, Postgres) | Platform team | 24×7 rotation | |

Every module has exactly one owning team; cross-module PRs need owner review from each touched module (enforced via CODEOWNERS).

## Decision and Documentation Governance

- **ADRs**: any decision that changes a published contract, a financial invariant, a data type carrying money, or a regulatory control gets an ADR (lightweight: context, decision, consequences) committed alongside the change
- **Doc-drift rule**: a PR that changes implemented behavior covered by these design docs must update the doc in the same PR — `implementation-notes.md` records deltas only until the design doc is amended, never as a permanent fork
- **Review cadence**: docs 00–17 reviewed quarterly by module owners; failure scenarios (11) re-validated against the last quarter's incidents
- **Regulated change management**: production changes follow the process in 13-deployment-architecture (CAB approval for ledger-touching changes) — governance here covers *design*, that covers *release*

## Infrastructure Cost Estimate (Indicative)

Order-of-magnitude, V1 scale (payment rails live, single region, Multi-AZ everything — banking gets no single-AZ discount). Validate against actual cloud pricing; figures are for relative weight and budgeting conversations, not procurement.

| Component | Sizing | ~Monthly (USD) |
|---|---|---|
| PostgreSQL (RDS Multi-AZ, r6g.2xlarge + replicas ×2) | Primary + standby + 2 read replicas | 4,000–6,000 |
| EKS application nodes | 8–12 nodes (m6g.xlarge) | 1,500–2,500 |
| Kafka (MSK, 3 brokers) | kafka.m5.large ×3 + storage | 1,000–1,500 |
| Redis (ElastiCache, Multi-AZ) | 2-node replication group | 400–700 |
| Observability (Prometheus/Grafana/ELK self-hosted + storage) | | 800–1,500 |
| S3 + backups + archival (journal partitions, audit) | Grows ~linearly | 300–800 |
| Networking, WAF, secrets, misc | | 500–1,000 |
| **Total** | | **~8,500–14,000/mo** |

**Cost drivers and levers:**
- Database is the dominant line — and the one you do *not* economize on (Multi-AZ and PITR backups are non-negotiables per the roadmap)
- Journal partition archival to S3/Glacier after 2 years keeps Postgres storage flat — the archival job pays for itself
- Non-prod environments often exceed prod cost in young banks: schedule dev/UAT clusters off-hours, keep one always-on staging that mirrors prod topology (regulator demos need it)
- People cost dwarfs infra at this scale: ~$10K/mo infra vs a 6–10 engineer team — infra optimization below ~20% savings is rarely worth engineer-weeks

## Interview Discussion Points

- Why CODEOWNERS-per-module is the monolith's substitute for microservice team boundaries
- Why doc-drift is a governance failure mode, not a hygiene nit — auditors read the design docs and test reality against them
- Why the database line item is the wrong place to cut cost in a banking system
