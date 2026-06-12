# 18 — Ownership, Governance, and Infrastructure Cost: Double-Entry Ledger Service

## Objective

Ownership model, decision governance, and indicative run cost for the ledger. A core financial primitive consumed by other teams needs *contract governance* more than most systems — its consumers (payments, loans, wallets) plan around its API and event schemas.

---

## Ownership

| Area | Owner | On-call |
|---|---|---|
| Posting path, journal, snapshots | Ledger team (single team owns the whole service) | 24×7 |
| Published event contracts (Kafka) | Ledger team as producer; schema changes need consumer sign-off | — |
| Reconciliation jobs + nightly soak | Ledger team | Mismatch alerts page immediately (P1) |
| Migration/backfill tooling (15-roadmap, Adoption) | Ledger team, run jointly with adopting team | Per-migration |

Single-team ownership is deliberate: the ledger is one bounded context (01-high-level-architecture), and splitting ownership of journal vs snapshots invites the exact snapshot-drift bugs the design defends against.

## Contract Governance (the important part)

- **API and event schemas are versioned contracts**: breaking changes require a deprecation window negotiated with every registered consumer; Schema Registry compatibility checks enforce backward compatibility in CI
- **Consumer registry**: every downstream consumer (fraud, analytics, reporting, adopting platforms) is registered with a named owner — "who consumes `posting.created`" must be answerable in minutes during an incident
- **ADRs** for: invariant-adjacent logic, schema/partitioning changes, idempotency semantics, anything touching the Adoption/migration contract
- **Doc-drift rule**: behavior changes update docs 00–17 in the same PR
- New adopters onboard through the Adoption process (15-roadmap) — no direct DB access ever; the posting API is the only write path

## Infrastructure Cost Estimate (Indicative)

Order-of-magnitude at V1 scale (5,000 postings/sec design point, single region).

| Component | Sizing | ~Monthly (USD) |
|---|---|---|
| PostgreSQL (RDS Multi-AZ r6g.2xlarge, partitioned journal) | Primary + standby + 1 replica | 3,500–5,000 |
| EKS app nodes (posting + read paths) | 6–8 nodes | 1,200–1,800 |
| Kafka (MSK 3 brokers) | | 1,000–1,500 |
| Redis (balance + idempotency cache, clustered) | | 500–900 |
| Observability | | 500–1,000 |
| S3 archival (partitions > retention) | | 200–500 |
| **Total** | | **~7,000–10,500/mo** |

**Cost shape:** storage grows linearly with posting volume forever (append-only, 7-year retention) — the partition-archival pipeline is a cost feature, not housekeeping. Compute scales with RPS phases (07-scaling-strategy); don't pre-provision Phase 4 CQRS infra at Phase 1 volume. Cost per million postings ≈ $50–80 at V1 utilization — useful as the chargeback unit if internal teams are billed for usage.

## Interview Discussion Points

- Why a shared financial primitive needs consumer-registry discipline that a product service can skip
- Why single-team ownership of journal + snapshots is a correctness decision
- Append-only economics: when does archival become mandatory rather than optional (answer: it was mandatory at design time — retrofitting partition archival on a 5TB hot table is the expensive path)
