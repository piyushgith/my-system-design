# 18 — Ownership, Governance, and Infrastructure Cost: Loan Origination & Servicing System

## Objective

Ownership, governance, and run cost. Distinctive here: the system orchestrates three sibling core systems (ledger, KYC, scoring — see 01, Relationship to Sibling Core Systems), so governance includes *consumer-side contract discipline*, and several owners are business functions (credit policy, collections, compliance) rather than engineering teams.

---

## Ownership

| Area | Owner | On-call |
|---|---|---|
| Origination modules (application, underwriting workflow) | Origination team | Business hours + campaign windows |
| Servicing modules (loan accounts, EMI, schedules) | Servicing team | 24×7 — EMI batch window paged |
| Collections module | Servicing team builds; **collections ops owns queues/SLAs** | Ops escalation |
| Disbursement saga | Origination team | 24×7 — stuck saga is P1 (Failure 1) |
| Credit policy rules (thresholds, DTI bands, maker-checker matrix) | **Credit risk team** — config with approval trail, not code | — |
| Regulatory reporting (F23–F28) | Servicing team builds; **compliance owns content and submission sign-off** | Submission-window pager |
| Sibling-system contracts (ledger/KYC/scoring integration) | Each integration has a named engineering owner on *this* side | — |

**Consumer-side contract discipline:** for each sibling system, the named owner tracks upstream contract changes (schema deprecations, new mandatory fields), keeps the contract tests in 17-testing-strategy current, and is the incident contact when the integration breaks. "The scoring API changed and nobody here noticed" must have an owner to blame.

## Decision Governance

- ADRs for: amortization/day-count convention changes (these are *money* decisions — fixture changes required in the same PR per 17-testing-strategy), saga step changes, new loan products, classification-rule changes (regulatory)
- Doc-drift rule: behavior changes update docs 00–17 in the same PR
- Credit policy changes: credit risk approves, recorded in the approval workflow, deployed as config — engineering reviews only for mechanical validity
- Quarterly: failure scenarios re-validated; reconciliation mismatch trends reviewed jointly with finance

## Infrastructure Cost Estimate (Indicative)

Single region, V1–V2 scale (auto-underwriting, NACH collection, ~100K active loans). Loan systems are cheap to run relative to the money they move — the cost story is dominated by data/bureau fees and people, not compute.

| Component | Sizing | ~Monthly (USD) |
|---|---|---|
| PostgreSQL (RDS Multi-AZ r6g.xlarge + replica) | | 2,000–3,000 |
| EKS app + batch nodes | 5–8 nodes (batch burst on spot) | 1,000–1,600 |
| Kafka (MSK 3 brokers) | | 1,000–1,500 |
| Redis | | 300–500 |
| S3 (loan documents, generated statements, report archives) | | 300–700 |
| Observability | | 500–1,000 |
| **Total infra** | | **~5,000–8,500/mo** |

**The bigger lines (outside infra, budget them anyway):** bureau pulls (₹15–50 per pull × application volume — the 24h cache in 09-caching-strategy is a cost control), NACH/payment-processor per-transaction fees on every EMI (× 100K loans monthly — negotiate slabs early), scoring/KYC internal chargeback if siblings bill per use. EMI batch is the only real compute scaling event: V3's 2M-loan batch is a Spring Batch partitioning problem (07-scaling-strategy), not a bigger-database problem — resist instance-size escalation as the answer.

## Interview Discussion Points

- Policy-as-config with business-function ownership: how credit risk changes thresholds without engineering deploys, and why the approval trail is the control
- Consumer-side contract ownership for shared core systems — the integration breaks on *your* roadmap, not theirs
- Why loan-system infra is cheap and where the real money leaks (bureau fees, payment rails, manual ops)
