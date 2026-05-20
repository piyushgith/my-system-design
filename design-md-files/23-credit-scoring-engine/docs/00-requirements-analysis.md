# 00 — Requirements Analysis: Credit Scoring Engine

---

## Objective

Define the functional and non-functional requirements, constraints, assumptions, and capacity estimates for a production-grade credit scoring engine used to drive lending decisions.

---

## Functional Requirements

### Core (MVP)

- Expose a synchronous scoring API: given a user_id, return a credit score within 200ms
- Compute scores from a combination of: bureau data (CIBIL/Experian), behavioral signals (transaction history), and account performance data
- Score range: 300–900 (standard CIBIL scale)
- Support multiple score types: CONSUMER score (individual), BUSINESS score (MSME/SME)
- Store every score computation with inputs and model version for audit
- Return score + reason codes (top 4 factors that influenced the score)

### Extended (V1)

- Batch score refresh: nightly job refreshes scores for all active loan applicants and existing borrowers
- Score versioning: models are versioned; each score request can specify model version or use "latest"
- Thin-file / new-to-credit (NTC) scoring: handle applicants with no bureau history using alternative data (UPI transaction history, utility bill payments)
- Score change events: publish `credit.score.updated` Kafka event when a user's score changes significantly (± 20 points)
- Champion-challenger scoring: 90% of requests use champion model, 10% use challenger model — compare performance over time

### Advanced (V2+)

- Real-time feature computation: compute behavioral features in < 50ms from streaming transaction data (Kafka Streams)
- Adverse action notices: auto-generate the reason codes in regulatory-compliant format (ECOA/FCRA) for declined applications
- Score drift monitoring: detect when score distribution shifts over time — model degradation signal
- Model fairness audit: detect and quantify bias in scoring across demographic groups (ECOA protected attributes)
- Explainability API: SHAP values for each feature's contribution to the score — required for regulatory review

---

## Non-Functional Requirements

| Property | Target |
|---|---|
| Scoring API latency (P99) | < 200ms (synchronous, real-time scoring) |
| Batch scoring throughput | 1M borrowers/hour |
| Availability | 99.99% — loan decisions depend on this |
| Score reproducibility | Same inputs → same score (deterministic) |
| Model versioning | Every score traceable to exact model version and input features |
| Audit | Every score computation stored with all inputs — required by ECOA/RBI |
| Data freshness | Bureau data refreshed every 30 days; behavioral features updated daily |
| Explainability | Reason codes (top 4 factors) returned with every score |

---

## Assumptions

- Bureau data (CIBIL/Experian) is fetched by the bureau integration service and stored in the feature store — the scoring engine reads from the feature store, not directly from bureau APIs
- The scoring model is a trained ML model (XGBoost or Logistic Regression) deployed as a ONNX model file — the scoring engine loads and serves it
- Model training happens offline (data science team) — the scoring engine only does inference
- Reason codes are pre-defined regulatory-compliant strings mapped from feature importance scores
- The scoring engine is an internal service — not exposed to end users directly
- Transaction history is provided by the internal transaction/ledger service via the feature store
- FI (Financial Information) data from Account Aggregator (AA) framework is provided by an AA integration service

---

## Constraints

- Bureau data is consent-gated: scoring engine can only access bureau data if the user has given consent (tracked by consent management service)
- Model fairness: scoring cannot use protected attributes (gender, religion, caste, ethnicity) as input features (ECOA compliance)
- Every score returned must include reason codes — cannot return a score without explanation
- Score computations cannot be deleted — immutable audit log required (regulatory)
- Model changes require a model validation report before deployment (risk team approval)
- RBI mandates: adverse action notices must be provided to declined applicants within 30 days

---

## Scale Estimation

### Traffic Assumptions

| Metric | Value |
|---|---|
| Real-time scoring requests/day | 500,000 |
| Peak real-time RPS | 50 RPS (loan application spikes) |
| Batch scoring jobs | Nightly: 5 million borrowers |
| Bureau pulls per day | 100,000 (fresh bureau data for new applications) |
| Feature store reads per score | 15–20 feature groups |
| Score history records/month | ~5M |

### Compute Estimation

| Task | Duration | Notes |
|---|---|---|
| Feature retrieval from Redis | 2–5ms | 15–20 feature keys |
| Model inference (XGBoost, 50 features) | 1–3ms | CPU inference on pre-loaded model |
| Score storage (PostgreSQL) | 5–10ms | Async; not on critical path |
| Reason code computation | 1–2ms | SHAP values or pre-computed coefficients |
| **Total scoring latency** | **~15ms P50** | Well within 200ms target |

### Storage Estimation

| Data | Size | Volume |
|---|---|---|
| Feature store (Redis) | 2 KB/user | 5M users × 2 KB = 10 GB |
| Score history (PostgreSQL) | 500 bytes/score | 5M/month × 500B = 2.5 GB/month |
| Model files (ONNX) | 50–500 MB per model | 10 model versions = 5 GB max |
| Bureau raw data (S3) | 50 KB/report | 100K/day × 50 KB = 5 GB/day |

---

## Read/Write Patterns

| Operation | Pattern | Frequency |
|---|---|---|
| Real-time score request | Feature read (Redis) + model inference + score write | 50 RPS |
| Batch score refresh | Sequential feature read + batch inference + bulk insert | 1M/hour |
| Feature store update | Write on transaction event / bureau refresh | High (many events) |
| Score history read | Point lookup by user_id + timestamp | Medium |
| Model update (champion) | Load new model file, hot-swap | Rare (monthly) |

---

## Latency Expectations

| Operation | Target P50 | Target P99 |
|---|---|---|
| Real-time score API | < 15ms | < 200ms |
| Batch score throughput | 1M in < 1 hour | — |
| Feature store write (async) | < 50ms | < 200ms |
| Score history read | < 50ms | < 200ms |
| Bureau data freshness | Updated within 30 days | — |

---

## Availability Targets

| Component | Availability | Notes |
|---|---|---|
| Real-time scoring API | 99.99% | Loan application pipeline depends on it |
| Batch scoring job | 99.5% | Can retry next day if failed |
| Feature store (Redis) | 99.99% | Critical for real-time path |
| Model inference engine | 99.99% | Fall back to cached score if unavailable |

---

## Tradeoffs Acknowledged at Requirements Level

| Decision | Tradeoff |
|---|---|
| Feature store pre-computation | Fast scoring (<5ms feature read), but features can be stale (up to 24h for batch-updated features) |
| Synchronous real-time scoring | Simple integration for callers, but scoring service is in the critical path for loan decisioning |
| Reason code pre-computation | Regulatory compliance, but adds 1–2ms to every score |
| Champion-challenger in production | Scientific rigor for model comparison, but operational complexity (two models running simultaneously) |
| Score caching | Sub-1ms for repeated requests in the same session, but cached score may not reflect a payment made 2 minutes ago |

---

## Interview Discussion Points

- **What is a credit score technically?** A credit score is a machine learning model's output (0–1 probability of default) transformed to a human-readable scale (300–900). A higher score = lower probability of default = lower credit risk. The model takes behavioral features (payment history, credit utilization, inquiry count) as input and outputs a risk prediction
- **What is CIBIL and how does it relate to the scoring engine?** CIBIL (TransUnion CIBIL) is India's primary credit bureau — maintains payment history across all lenders. The scoring engine uses CIBIL data as one input among many. CIBIL itself provides a score (300–900), but the scoring engine may produce its own internal score (which can be better-calibrated for the lender's specific portfolio)
- **Why build your own scoring model instead of just using CIBIL's score?** CIBIL's score is generic. A lender's own model can: (1) include proprietary behavioral data (UPI transaction patterns, internal account history) that CIBIL doesn't have, (2) be calibrated for the lender's specific borrower population and product type, (3) be updated more frequently (CIBIL updates monthly; lender can update weekly)
- **What is the "thin file" problem?** 40% of India's adult population has no credit bureau history (NTC — new to credit). CIBIL's model returns no score for these users. The scoring engine handles NTC users with alternative data: UPI payment history, digital footprint, utility bill payments, and bank statement analysis — enabling credit access for the unbanked
