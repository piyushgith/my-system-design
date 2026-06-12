# 18 — Ownership, Governance, and Infrastructure Cost: Credit Scoring Engine

## Objective

Engineering ownership and run cost. Model governance (validation, promotion authority, inventory, revalidation) is already defined in 13-deployment-architecture §Model Governance and Risk Management — this document covers everything around it and deliberately does not restate it.

---

## Ownership

| Area | Owner | On-call |
|---|---|---|
| Scoring engine application, APIs, hot-reload | Scoring engineering | 24×7 (loan decisions block on it) |
| Feature pipeline (Kafka Streams, batch features) | Scoring engineering | 24×7 — staleness is a lending-quality incident |
| Feature store schema (`feature_definitions`) | Joint: data science defines, engineering operates | — |
| Models (training, candidates, golden sets) | Data science | Business hours |
| Model promotion authority | Independent validator / risk team (per 13-deployment) | — |
| Bureau/AA integration services | Separate integrations team (upstream of this system) | Their pager |

**Boundary rule:** data science never deploys to production directly — the registry + `approval_token` flow is the only promotion path. Engineering never edits model files — applications serve what the registry points at. The `feature_definitions` table is the contract both sides write to, which is why training-serving skew tests (17-testing-strategy) gate changes to it from either side.

## Decision Governance

- ADRs for: feature store schema changes, new feature data sources (consent implications), serving-architecture changes (e.g., ONNX-in-process → model server at Phase 4), fallback-contract changes (callers depend on it)
- Doc-drift rule: behavior changes update docs 00–17 in the same PR
- Quarterly: model inventory review (already in 13-deployment); plus engineering review of fallback-served rate and skew metrics — sustained fallback > 0.1% of scores is an architecture problem, not an ops annoyance

## Infrastructure Cost Estimate (Indicative)

Single region, V1 scale (50 RPS real-time + 5M nightly batch). In-process ONNX keeps this cheap — that was the point of rejecting SageMaker at this scale (01-high-level-architecture).

| Component | Sizing | ~Monthly (USD) |
|---|---|---|
| EKS scoring + feature pipeline nodes | 4–6 nodes (m6g.xlarge) | 800–1,200 |
| Redis feature store (cluster, 32GB) | | 800–1,400 |
| PostgreSQL (RDS Multi-AZ, score_history partitioned) | | 1,500–2,500 |
| Kafka (MSK 3 brokers) | | 1,000–1,500 |
| Batch scoring burst capacity (nightly CronJob nodes) | Spot instances | 200–500 |
| S3 (models, archived score history) + observability | | 500–1,000 |
| **Total** | | **~5,000–8,000/mo** |

**Cost notes:** bureau data fees are the scoring *ecosystem's* dominant cost but sit in the bureau integration service's budget, not here — same lesson as KYC: data acquisition dwarfs infra. The managed-ML-platform decision flips when either RPS grows 10× or data science team size makes platform tooling worth its premium — revisit at Phase 4, per the architecture doc. Redis feature store memory grows with feature count × user base: feature additions need a memory estimate in the ADR, not just a schema row.

## Interview Discussion Points

- Why promotion authority sits outside both teams that build the thing (separation of duties as architecture)
- The `feature_definitions` table as a two-team contract surface — and what test gates protect it
- When in-process model serving stops being the right answer (the cost crossover, not a fashion change)
