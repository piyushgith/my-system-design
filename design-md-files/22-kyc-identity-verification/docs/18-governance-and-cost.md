# 18 — Ownership, Governance, and Infrastructure Cost: KYC / Identity Verification Pipeline

## Objective

Ownership model, governance, and cost shape. Distinctive here: total cost is dominated by **vendor spend, not infrastructure** (see 00-requirements, Vendor Cost Model), and governance is shared with a non-engineering function — compliance owns decisions engineering merely implements.

---

## Ownership

| Area | Owner | On-call |
|---|---|---|
| Pipeline, state machine, APIs | KYC engineering | 24×7 (onboarding is revenue path) |
| Vendor adapters | Named owner per vendor (adapter, contract suite, sandbox creds, cost tracking) | Vendor outage runbooks per adapter |
| Manual review queue + dashboard | KYC engineering builds; **operations team owns queue SLA** | Ops escalation per Failure 7 |
| KYC policy (tiers, thresholds, watchlist actions) | **Compliance team** — engineering implements as config | — |
| PII lifecycle (encryption, purge, DPIA) | KYC engineering + compliance co-own (per 08-security-design) | Purge-job failure pages |

**Engineering/compliance boundary:** compliance decides *what* (which checks per tier, confidence thresholds, watchlist match disposition); engineering decides *how* (pipeline, vendors' technical integration, scaling). Threshold changes are config deployments with compliance approval recorded — not code PRs debated by engineers.

## Decision Governance

- ADRs for: vendor add/remove (requires signed DPA first — 08-security-design), state machine changes, retention changes, new data categories (triggers DPIA re-run)
- Doc-drift rule: behavior changes update docs 00–17 in the same PR
- Quarterly: vendor scorecard review (cost, accuracy drift from 12-observability, outage minutes) feeds contract renewals; failure scenarios re-validated against incidents

## Cost Estimate (Indicative)

The split matters more than the totals:

| Bucket | ~Monthly (USD) | Share |
|---|---|---|
| **Vendor spend** (per 00-requirements Vendor Cost Model, 10K apps/day) | **80,000–160,000** | ~90%+ |
| Infrastructure (below) | 4,500–8,000 | < 10% |

Infrastructure breakdown (single region, V1):

| Component | Sizing | ~Monthly (USD) |
|---|---|---|
| PostgreSQL (RDS Multi-AZ r6g.xlarge) | | 1,500–2,500 |
| EKS app + pipeline workers | 4–6 nodes | 800–1,200 |
| Kafka (MSK 3 brokers) | | 1,000–1,500 |
| Redis | | 300–500 |
| S3 (encrypted documents, 40GB/day ingest, lifecycle to Glacier) | | 400–1,200 |
| KMS + observability + misc | | 500–1,100 |

**Consequence:** an engineer-month spent shaving infra is worth ~$500/mo; the same month spent raising the automated-pass rate by 2 points or routing more traffic through DigiLocker saves thousands/mo in vendor and manual-review spend. Optimization priority order: (1) vendor routing/caching, (2) manual-review rate, (3) infra — permanently.

## Interview Discussion Points

- Why KYC unit economics, not throughput, drives this system's roadmap priorities
- The compliance/engineering ownership split: policy-as-config with approval trails vs policy-in-code
- Why each vendor adapter has a named owner (contract, cost, drift, sandbox) — vendors are dependencies with invoices, not libraries
