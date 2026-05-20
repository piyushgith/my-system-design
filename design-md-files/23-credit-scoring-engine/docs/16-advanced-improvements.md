# 16 — Advanced Improvements & Architecture Critique: Credit Scoring Engine

---

## Objective

Critique the architecture honestly: where it will fail, what corners were cut, what a Staff/Principal interviewer would challenge, and what advanced improvements would genuinely make it production-grade at scale.

---

## Architecture Weaknesses

### Weakness 1: Training-Serving Skew

**Problem:** The model is trained on historical features from the data warehouse (batch-computed aggregates with specific time windows). The feature store serves real-time features computed by Kafka Streams (slightly different aggregation logic, different time window boundaries). If the training pipeline computes `behavior.upi_txn_count_30d` using a calendar month boundary and the serving pipeline uses a rolling 30-day window, the model sees different feature distributions in production than in training.

**Impact:** Model calibration degrades over time. `raw_pd` may be systematically biased — model thinks PD is 0.08 when actual is 0.12. Leads to over-approval.

**Fix:** Feast point-in-time feature retrieval. Training data uses the same feature retrieval logic as serving (Feast offline store). Eliminates skew at the root. This is the primary architectural flaw in the current design — deferred to V3, but should be V1 priority in a real production system.

---

### Weakness 2: No Score Versioning at the Consumer Level

**Problem:** Loan Service stores the score it receives and uses it for the loan decision. When the model is promoted (new champion), scores from the old model and new model are both floating around in Loan Service's database. Loan Service has no way to know if a score from last week used `xgb-v2.3.1` (less accurate) or `xgb-v2.4.0` (new champion).

**Impact:** Downstream risk analytics and model performance attribution become unreliable. Which model decisions led to defaults?

**Fix:** Include `model_version` in every score response (already done in the design). Loan Service MUST store `model_version` alongside the score in its own loan application record. Scoring engine provides `model_version` — consumers must persist it. This requires coordination between scoring engine and all consumers — an API governance problem, not just a scoring engine problem.

---

### Weakness 3: Feature Store Not Designed for Training

**Problem:** Redis feature store holds current values only (no history). To retrain the model on historical data, you need to know what the feature values were 6 months ago for users who defaulted. Redis TTL-based eviction means historical feature values are permanently gone.

**Impact:** Model retraining requires reconstructing historical features from raw events (transaction history, bureau reports) — expensive, complex, and potentially impossible if bureau reports are no longer available (CIBIL API doesn't serve historical reports).

**Fix:** Dual-write features to both Redis (serving) and BigQuery (training). BigQuery serves as the offline feature store. Feature snapshots stored in `score_history` partially mitigate this (features at scoring time are preserved), but don't cover users who were never scored during the training window.

---

### Weakness 4: Champion-Challenger Is Not Truly Blinded

**Problem:** The scoring engine routes 10% of users to the challenger model. If the loan service's underwriting team notices that certain users seem to get different score outcomes on the same day (user applies twice — once gets challenger, once gets champion if the first request was cached with champion score), they might inadvertently attribute decisions differently. Additionally, if the challenger consistently gives 10-point-lower scores, 10% of users are systematically disadvantaged during the challenger evaluation period.

**Impact:** Regulatory risk during challenger period. Potential for disparate treatment claims.

**Fix:** 
1. Sticky routing: once a user is assigned to challenger (hash-based), they always get challenger for the entire evaluation period. No mixing within the same user's requests
2. Harm ceiling: if challenger score is > 30 points lower than champion for the same user (computed in shadow mode first), block challenger promotion and alert data science team
3. Challenger evaluation: compare only aggregate outcomes (Gini, default rates) — not individual score comparisons

---

### Weakness 5: No Rate-of-Change Limiting on Feature Updates

**Problem:** A compromised feature pipeline (or a bug in the bureau data ACL) could push wildly incorrect feature values to Redis. For example: `bureau.cibil_score = 0` for 100K users (due to a mapping bug). The next batch scoring run would give all 100K users VERY_POOR scores. Loan applications would be auto-declined until the bug is detected.

**Impact:** Mass incorrect credit decisions. Regulatory violation if loan rejections are triggered by incorrect data.

**Fix:** Feature store write validation:
```
Before MSET:
  bureau.cibil_score: reject if outside [300, 900]
  bureau.credit_utilization: reject if outside [0, 2.0]  (> 1 means over-limit)
  bureau.dpd_last_6m: reject if outside [0, 180]  (max 6 months of days)
```
Feature pipeline validates against `feature_definitions.data_type` and range constraints before writing. Additionally: daily reconciliation job compares feature value distributions against prior day — alert if P50 changes > 50 points for bureau.cibil_score.

---

### Weakness 6: ONNX Hot-Reload Window

**Problem:** During hot-reload, there is a window (typically 5–30 seconds) where different pods run different model versions. Pod A has loaded the new champion. Pod B is still downloading. Pod C is still running old champion.

In this window:
- Same user_id routes to Pod A → new champion score = 750
- Same user_id routes to Pod B (if Pod A busy) → new champion score = 752 (same model, negligible difference)  
- But if user_id routes to Pod C → old champion score = 730 (20 points different, could trigger different loan decision)

**Impact:** Inconsistent decisions during hot-reload window. If the 20-point difference crosses a policy threshold (e.g., > 720 → pre-approved, < 720 → manual review), the user might get different outcomes depending on which pod handled their request.

**Fix:** For high-stakes decisions during model transition: Loan Service should cache the model_version from the first score response. If the model_version changes on a subsequent request within the same loan application session, force a final score refresh with the new model version to ensure consistent decision. Alternatively: coordinate hot-reload to happen during very low traffic window (2 AM), where the 30-second inconsistency window has minimal impact.

---

## Advanced Improvements

### Improvement 1: Counterfactual Scoring ("What Would It Take to Get Approved?")

**Current state:** user receives adverse action reason codes — "too many delinquencies." They don't know what they need to change to improve their score.

**Advanced improvement:** counterfactual explanations. Given the current feature vector, compute the minimum feature changes that would push the score above a threshold (e.g., from 620 to 700).

Implementation: DiCE (Diverse Counterfactual Explanations) library + ONNX model. On adverse action: run DiCE against feature vector, return top 3 actionable changes ("Reducing credit utilization from 85% to 40% would improve your score by ~40 points").

Regulatory status: ECOA already requires reason codes; counterfactuals are an enhancement, not required. But significantly improves user experience and reduces consumer complaints.

---

### Improvement 2: Real-Time Model Calibration Check

**Current state:** model calibration is checked monthly (PSI job). Model can drift for weeks before detection.

**Advanced improvement:** online calibration monitoring. For every 1000 real-time scores, compute the actual approval rate vs expected approval rate at each score decile. If observed approval rate diverges from expected by > 5%, trigger calibration alert.

Implementation: Kafka Streams running alongside the scoring engine, consuming `credit.score.computed` events. Computes a rolling Hosmer-Lemeshow statistic (calibration measure). Alert fires to data science team before monthly PSI job would catch it.

---

### Improvement 3: Federated Feature Store for Multi-Bureau Support

**Current state:** single CIBIL bureau integration. India has CIBIL, Experian India, CRIF, Equifax India as licensed credit bureaus.

**Advanced improvement:** multi-bureau feature aggregation. Pull bureau data from 2–3 bureaus (per RBI guidelines for housing loans > ₹50L), reconcile conflicts, produce a consensus feature vector.

Implementation:
- `bureau.cibil_score`, `bureau.experian_score`, `bureau.crif_score` as separate feature keys
- `FeatureAssemblyService.reconcile()`: weighted average or max-of-conservative approach
- Feature pipeline: separate Kafka consumers per bureau integration service

---

### Improvement 4: Behavioral Scoring for Non-Bureau Users

**Current state:** thin-file users (< 6 months credit history, no CIBIL score) get a degraded "NTC model" score based on limited features.

**Advanced improvement:** alternative data scoring. NTC model trained on:
- UPI transaction velocity (consistent daily transactions = stable income)
- Salary credit regularity (salary_credit_streak_months > 6 = stable employment)
- Bill payment behavior (electricity, mobile, DTH payments on time)
- Account Aggregator bank statement data (cash flow analysis, EMI obligations)

These behavioral signals can produce a score competitive with bureau scores for NTC users. Implementation: separate ONNX model (`nonthin-credit-v1.0.onnx`) with 25 behavioral features instead of bureau features. Same inference path, different feature assembly logic.

---

### Improvement 5: Explainability Audit for Fairness

**Current state:** model fairness checked monthly via disparate impact ratio.

**Advanced improvement:** continuous SHAP-based fairness monitoring. For every score computed:
1. Which features are driving the score?
2. Are certain features acting as proxies for protected attributes (geography as proxy for race/religion, age of oldest account as proxy for age discrimination)?

Implementation: Kafka Streams computing rolling SHAP value distributions per demographic group. Alert if a feature's SHAP value shows > 10% more impact for one demographic vs another, not explainable by underlying credit quality difference.

---

## Scaling Limits Analysis

| Dimension | Current Limit | Limit Reached At | Solution |
|---|---|---|---|
| Real-time RPS | 50 RPS (3 pods) | ~150 RPS (HPA ceiling) | More pods, or Triton model server |
| Batch throughput | 5M users/night | ~15M before Redis MGET saturates | Spark on EMR, dedicated batch Redis cluster |
| Feature store size | ~20 GB (10M users) | ~64 GB (ElastiCache r6g.xlarge limit) | Redis cluster mode or larger instance |
| score_history rows | 5M/month | ~500M rows (~7 years) | Partitioned archival to S3 Parquet |
| model_registry size | Indefinite (small) | No practical limit | Never a concern |
| Kafka consumer lag | bureau: < 60s | > 1000 events/sec per partition | Add partitions, scale consumer group |

---

## Tech Debt Risks

| Debt | Risk Level | When It Bites |
|---|---|---|
| Training-serving skew | HIGH | When model accuracy degrades silently (monthly retraining won't fix it) |
| No feature value validation at write time | HIGH | First time a bug pushes invalid feature values → mass wrong scores |
| SHAP not stored for batch scores | MEDIUM | Regulatory audit requests explanation for a batch-scored decision → must recompute |
| Single Redis instance for feature store | MEDIUM | Redis node failure → scoring falls back to PostgreSQL (degraded mode) |
| Manual partition creation for score_history | LOW | DBA forgets to create next month's partition → INSERT fails (mitigated by pg_partman) |

---

## Operational Burden Assessment

| Operation | Frequency | Effort | Risk |
|---|---|---|---|
| Monthly partition pre-creation | Monthly | Low (pg_partman automates) | Low |
| Model validation and promotion | Quarterly | High (data science + risk + engineering) | Medium (model regression risk) |
| Bureau API credential rotation | Annually | Low | Low |
| Feature pipeline Kafka offset management (DLQ) | Weekly (when active) | Medium | Low |
| Score history archival to S3 | Monthly (automated) | Low | Low |
| Redis memory capacity review | Quarterly | Low | Medium (if over-provisioned or under-provisioned) |
| Model fairness audit | Monthly | Medium (data science) | High (regulatory) |

---

## What a Staff Interviewer Would Challenge

1. **"Your score cache invalidation on bureau update uses SCAN + DEL. SCAN is O(N) and can block Redis."**
   Counter: SCAN with COUNT=100 is non-blocking (cursor-based, releases after each batch). 4 keys to delete (4 product types) → single SCAN iteration is near-instant. If keyspace grows to millions: use Redis key namespacing to avoid full SCAN (e.g., `score:{user_id}:*` could be tracked separately). Pre-emptively: store score cache keys in a Redis Set per user_id. On invalidation: SMEMBERS → targeted DEL. Removes SCAN entirely.

2. **"Your champion/challenger hash routing is deterministic. Can an attacker predict which model serves them?"**
   Counter: user_id is a UUID (not sequential, not guessable). Hash of UUID % 100 is effectively random from the attacker's perspective. Unless the attacker knows the user_id mapping (which is internal), they cannot predict routing. The score response includes `model_version` but not `model_role` — so even if they can see the response, they cannot determine if it was champion or challenger.

3. **"You said batch scoring completes in 15 minutes with 50 parallel workers. How do you prevent 50 workers from creating a thundering herd on the feature store Redis?"**
   Counter: Spring Batch partitions by user_id range (not random). Each worker handles 100K users → 100K × 20 features = 2M MGET calls per worker. Rate limiting: Guava RateLimiter at 50K MGET/sec per worker = 2.5M total MGET/sec across 50 workers. Redis cluster (3 nodes) handles 3M ops/sec. Headroom exists. Additionally: pipeline MGET (batch 100 users per MGET, not 1) → 100K users = 1000 pipelined MGET calls instead of 100K → 100× fewer round-trips.
