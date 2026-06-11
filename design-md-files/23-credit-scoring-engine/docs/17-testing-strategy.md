# 17 — Testing Strategy: Credit Scoring Engine

## Objective

Define test layers and release gates. A scoring engine has two artifacts with independent lifecycles — the *application* and the *model* — and each needs its own gates (the deployment doc separates their pipelines; this separates their tests). The non-negotiables: reproducibility, the fallback contract, and the fairness launch gate.

---

## Test Pyramid

| Layer | Scope | Tooling | Runs |
|---|---|---|---|
| Unit | Feature assembly, reason-code mapping, score banding, consent checks | JUnit 5 | Every commit |
| Integration | Scoring path on real Redis + PostgreSQL; ONNX runtime loading | Testcontainers | Every commit |
| API contract | gRPC/REST schemas, error codes, fallback headers | Contract suite | Every PR |
| Model gates | Per model version: golden scores, fairness, skew (below) | MLOps pipeline | Every model registration/promotion |
| Chaos / NFR | Failure-scenario drills from doc 11 | Scheduled chaos suite | Weekly in staging |

## Application Tests (release-gating, per app release)

1. **Reproducibility**: re-score from a stored feature snapshot → bit-identical score and reason codes, for every supported model version (the audit-trail promise, tested)
2. **Fallback contract**: Redis down → response carries `source=FALLBACK_CACHE` + `score_age_seconds`; no score history → `503 FEATURE_STORE_UNAVAILABLE`; never a silent default score (Failure 1 as a test, not just a runbook)
3. **Consent enforcement**: expired/absent consent → bureau features excluded or request rejected per policy; consent cache staleness bounded
4. **Idempotency**: duplicate score requests with one idempotency key → one `score_history` row
5. **Hot-reload safety**: corrupted/hash-mismatched model file → load rejected, old champion keeps serving, alert fired (Failure 2); reload under live traffic drops zero requests
6. **Champion-challenger routing**: traffic split honored within tolerance; a user pinned to a variant scores consistently within a session; challenger scores never returned to callers

## Model Version Gates (per model registration — distinct from app CI)

1. **Golden-score regression set**: fixed reference population scored by every candidate; deltas vs current champion summarized — large unexplained shifts block promotion (Failure 8 prevention)
2. **Fairness audit** (launch gate per 00-requirements): disparate impact ratios across protected groups on out-of-time holdout; result attached to the model validation report; promotion API rejects without validator approval (see 13-deployment, Model Governance)
3. **Training-serving skew gate**: offline-recomputed features vs serving feature store on a sample → `feature_skew_ratio` under threshold per input feature (see 12-observability); required when either feature pipeline changed
4. **Reason-code sanity**: top-4 reason codes for reference cases reviewed against the new feature importance ordering — codes must remain truthful, not just present
5. ONNX conversion equivalence: converted model output matches the source (XGBoost) model on the reference set within tolerance

## Batch & Pipeline Tests

- Nightly batch: 5M-user batch on staging-scale data completes in window; checkpoint/restart resumes without double-writing `score_history`
- Feature pipeline: Kafka replay of a day of transaction events → feature store converges to identical values (idempotent feature updates)
- Feature staleness: batch job failure → staleness metadata propagates, scores carry degraded-freshness indication (Failure 3)

## Chaos Suite (weekly, staging)

The chaos commands embedded in 11-failure-scenarios are the suite definition: Redis sleep, model-file corruption, Kafka consumer stall, inference latency injection. Each has an asserted expected behavior — a chaos run without assertions is a demo, not a test.

## CI Quality Gates

1. Application suites green; reproducibility + fallback-contract tests are hard gates
2. Model promotion blocked without: golden-score report, fairness pass, skew pass, validator `approval_token`
3. Avro/Schema Registry compatibility for all published events

## What NOT to Test

- Model *accuracy* in application CI — that is the model gate's job, on the data science side of the boundary
- Bureau API behavior — the bureau integration service owns that contract; we test against its published schema
- Re-deriving SHAP correctness — library responsibility; we test exposure control (scope-gated) and stability of the interface

## Ownership

Application suites: scoring engine team. Model gates: data science authors them, the independent validator (risk team) owns pass/fail authority — the same separation of duties as the governance process they feed.
