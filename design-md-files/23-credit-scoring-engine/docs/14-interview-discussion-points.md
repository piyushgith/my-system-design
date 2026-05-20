# 14 — Interview Discussion Points: Credit Scoring Engine

---

## Objective

Consolidate the highest-value interview discussion topics across all design dimensions. Organized by interviewer seniority and likely challenge areas. Includes common mistakes candidates make and counter-arguments to expect.

---

## Senior Engineer Discussion Points

### Fundamentals: Feature Store Design

**Q: Why precompute features instead of computing them at score time?**

A: At 50 RPS peak, computing features on the fly requires:
- SQL aggregations over 24 months of transaction history (millions of rows per user)
- Bureau API call (500ms–2s, ₹15–50 per call)
- Account aggregator bank statement parsing

That's 1–5 seconds per score. Unacceptable for a loan application flow. The feature store precomputes all aggregates and stores them as O(1) Redis key lookups. Trade-off: features can be up to 24 hours stale for batch-updated features. For final loan decisions: `force_refresh=true` bypasses score cache and reads fresh features.

**Q: How does the feature store maintain consistency across multiple features for the same user?**

A: It doesn't — strictly speaking. `MGET` retrieves 15–20 feature keys atomically (single round-trip), but those keys may have been updated at different times by different pipeline jobs. Bureau features updated January 1, behavioral features updated January 15. The feature vector is a "best available" snapshot — not a consistent point-in-time view.

For full consistency: the feature snapshot is stored in `score_history.feature_snapshot` JSONB. This captures exactly which values were used for each score. Even if features are updated 10 minutes later, the stored snapshot preserves the point-in-time view used for the decision.

---

### ONNX Model Serving

**Q: Why serve the ONNX model in-process instead of a dedicated model server?**

A: At 50 RPS with 15–20 features per request, model inference takes 1–3ms in-process. A dedicated model server (SageMaker, Triton) adds 5–15ms network round-trip overhead. The in-process approach: no network, no serialization, < 3ms inference. Trade-off: model update requires hot-reload mechanism (pod-by-pod via Kafka event + S3 download) instead of blue-green model deployment. Hot-reload adds operational complexity. Justified at this scale; migrate to Triton at 500+ RPS when inference cost dominates.

**Q: How do you handle JVM garbage collection pauses during ONNX inference?**

A: G1GC with `MaxGCPauseMillis=10` limits stop-the-world pauses. ONNX Runtime uses off-heap native memory (not JVM heap) for model weights and inference buffers — GC pauses don't directly impact inference time. The JVM heap is used for Java objects (request/response, feature vector assembly). Monitor: `jvm_gc_pause_seconds_max` alert > 50ms indicates tuning needed. ZGC is an alternative (sub-millisecond pauses) but requires Java 15+.

---

### Champion-Challenger Architecture

**Q: How do you ensure the challenger model doesn't disadvantage users who receive it?**

A: Two safeguards:
1. The challenger is only deployed after shadow validation — 7 days of side-by-side scoring shows the challenger scores are reasonable (not 50+ points off the champion)
2. The caller receives the score and reason codes — they don't know which model served. If the challenger systematically under-scores certain demographics, this is detected in the weekly approval rate monitoring and PSI analysis within days (not months)

Regulatory consideration: if the challenger shows disparate impact on protected classes (age, gender, religion) — detected via mandatory monitoring — it's rolled back immediately. Model fairness audits run before any challenger traffic is enabled.

---

## Staff Engineer Discussion Points

### Regulatory Architecture

**Q: What is your strategy for the 7-year score history retention requirement?**

A: Three-tier data management:
- Active tier: PostgreSQL partitioned by month. Queries touch only relevant partitions
- Warm tier: partitions older than 1 year archived to S3 Parquet (via pg_partman partition detach + COPY TO)
- Cold tier: S3 Glacier for partitions older than 5 years (lifecycle rule). Retrieval: 3–5 hours (acceptable for compliance audit)

The monthly partitioning enables clean archival: detach January 2023 partition → export to Parquet → delete from PostgreSQL. No row-level deletion needed. Compliance officers query active PostgreSQL for recent audits; historical queries go to S3 Athena.

**Q: A compliance officer requests all scores for a specific user over the past 7 years for a dispute. How do you serve this?**

A: 
1. Active PostgreSQL (last 2 years): `SELECT * FROM score_history WHERE user_id = ? ORDER BY computed_at` → partition pruning on user_id index
2. Warm S3 Parquet (2–7 years): Athena query on S3 partitioned by year/month
3. Merge results → return as paginated export (not all-at-once, potentially thousands of rows)
4. Feature snapshots included: compliance officer can see exactly which feature values drove each score
5. Full audit trail: which model version, what reason codes, was it a champion or challenger score

This is why `feature_snapshot` is stored per score rather than referenced to the (mutable) Redis feature store.

---

### CQRS and Consistency

**Q: The score cache has a 5-minute TTL. Is that appropriate for a final credit decision?**

A: No — and the design accounts for this. Score cache is appropriate for:
- Pre-screening queries (CRM marketing: is this user eligible for a pre-approved offer?)
- Internal eligibility checks (show user their credit health score on dashboard)
- Rate-limited exploratory queries

For final credit decisions (user clicks "Submit Application"), the Loan Service sets `force_refresh=true`. This bypasses the score cache entirely and forces fresh feature assembly from Redis. The fresh score is returned, stored in score_history, and used for the credit decision. The score cache serves repeat exploratory queries — not final decisions.

**Q: You store the full feature snapshot JSONB with every score. At 5M batch scores/night, that's a lot of storage. How do you manage this?**

A: Each feature_snapshot is ~2KB (15–20 features × ~100 bytes each as JSON). 5M rows/month × 2KB = 10 GB/month. Over 7 years: ~840 GB — manageable with column compression and partitioned archival.

Optimization: SHAP values NOT stored in batch scoring (saves 5M × 50 features × 8 bytes = ~2 GB/night). SHAP computed and stored only for real-time scores when requested (< 5% of requests use `X-Include-Shap: true`).

For batch, `feature_snapshot` is still stored (regulatory reproducibility requirement applies to batch too). If storage becomes a concern at 50M users: compress feature_snapshot column (PostgreSQL TOAST with LZ4 compression is automatic for large JSONB values).

---

### Scaling Evolution

**Q: At 10× current scale (500 RPS), what would break first?**

Priority order of failure:
1. **ONNX in-process inference:** at 500 RPS × 3ms = 1.5 seconds of inference per second per pod. With 3 pods: 500 RPS → ~167 RPS/pod. 4 vCPU pod: 4 threads × 3ms = 1333 RPS theoretical max but shared with feature assembly, network, etc. In practice: pods become CPU-bound at ~100 RPS/pod. Need 5–6 pods minimum, more with HPA. Solution: dedicated model serving pods (Triton), horizontal model scaling independent of API layer
2. **Redis feature store bandwidth:** 500 RPS × 20 MGET keys × 100 bytes = 1MB/s per pod × 12 pods = 12 MB/s → Redis cluster handles 100+ MB/s. Not a bottleneck yet
3. **PostgreSQL async write throughput:** 500 RPS → 500 score_history inserts/second. With batching and async: PostgreSQL handles 10K+ TPS. Not a bottleneck at this scale
4. **Kafka consumer lag:** feature pipeline must process more transactions (10× volume). Scale consumer groups proportionally

---

## Common Candidate Mistakes

| Mistake | Why It's Wrong | Better Answer |
|---|---|---|
| "Store feature values in the score_history table as foreign keys to the feature store" | Feature store (Redis) is mutable. FK reference would be invalid for audit in 6 months. | Snapshot: copy feature values into score_history.feature_snapshot JSONB at scoring time |
| "Call the bureau API synchronously on every score request" | Bureau API: 500ms–2s latency, ₹15–50/call. At 50 RPS: 50 × ₹50 = ₹2500/min = ₹3.6M/day | Feature store: bureau data pre-fetched, stored in Redis. Score request reads from Redis |
| "Use score.updated Kafka events to invalidate score cache" | Score cache is only invalidated when FEATURES change (bureau refresh, behavioral update). Not when a score is computed | Score cache invalidated on feature.profile.updated events (when Redis feature keys change) |
| "Return SHAP values for all score requests by default" | SHAP computation adds 1–2ms. At 500K requests/day: 83 hours of extra compute. Also reveals feature weights | Gate SHAP behind scope + header. Compute only when explicitly requested |
| "Use single Redis cluster for feature store + score cache" | Feature store: 32GB, 32-day TTL → rarely evicted. Score cache: small, 5-min TTL → frequently evicted. Mixing → eviction pressure affects feature store | Separate Redis clusters with different eviction policies |
| "Champion/challenger traffic split via separate API endpoints" | Callers would know which model served them. Defeats the purpose of blind A/B testing | Split within the scoring engine via user_id hash. Caller receives identical response format regardless of model |

---

## What Would Break First (Failure Priority)

```
Traffic spike: 10× normal RPS
  → First to fail: ONNX inference CPU saturation (per-pod)
  → Second: HPA pod provisioning lag (1–2 min to add pods)
  → Third: Redis score cache hit rate drops (more unique user_ids hit during spike)
  → Fourth: PostgreSQL async insert backlog grows (non-critical)

Feature pipeline outage (Kafka down):
  → Features stop refreshing: bureau features stale after 32 days, behavioral after 2 days
  → Silent degradation: scores computed on stale features, no errors returned
  → Detection: feature freshness SLO alert fires (bureau_as_of > 32 days for > 1% users)

Model promotion gone wrong:
  → Approval rate drops immediately (detectable within 2 hours)
  → Emergency rollback: < 5 minutes (API call + hot-reload)

PostgreSQL primary failure:
  → RDS Multi-AZ failover: < 60 seconds
  → Score computation continues (reads from feature store Redis during failover)
  → Score history writes queue in Kafka (audit writer retries)
  → Zero customer impact during 60-second failover
```

---

## Staff / Principal Engineer Challenges

### "The design is too simple — where would you take it in 2 years?"

**Honest answer:** The current design is intentionally scoped for 50 RPS / 5M batch. For 10× scale:

1. **Feast-managed feature store:** replace raw Redis keys with Feast (Feature Store framework). Benefits: point-in-time feature retrieval for training (avoids training-serving skew), feature sharing across ML teams, monitoring built-in
2. **Triton Inference Server:** dedicated GPU-backed model serving. Batches inference requests across concurrent callers (dynamic batching reduces GPU idle time). At 500 RPS: 10× better inference economics
3. **Online learning:** behavioral features currently computed by batch (T-24h lag). Online learning updates model incrementally on each transaction event — model stays calibrated to recent patterns without monthly retraining
4. **Explainability as a service:** SHAP computation on a dedicated sidecar pod (not in-process). Allows SHAP to scale independently from scoring throughput

### "How do you handle the case where the bureau changes their data format?"

BureauDataAdapter ACL: CIBIL XML → canonical feature names. The ACL is the only code that knows CIBIL's format. When CIBIL releases a new report format:
1. Update `BureauDataAdapter.translate()` method only
2. Canonical feature names don't change
3. All downstream consumers (feature store, model registry) are unaffected
4. Run regression test: old format + new format → same canonical feature values
5. Deploy via normal CI/CD pipeline

This is why the ACL pattern is critical for credit scoring: bureau APIs change frequently (CIBIL has released 3 major API versions in 5 years). The ACL absorbs format changes without touching the scoring core.

---

## Regulatory Discussion Points (Senior Level)

**Q: If a user disputes a loan denial, what information can you provide?**

1. Score at decision time (from score_history, permanently stored)
2. Model version that produced the score
3. Reason codes (ECOA-compliant English descriptions of adverse factors)
4. Feature snapshot (internal — not given to user, but available for compliance review)
5. Adverse action notice (generated by `GET /scores/{id}/adverse-action-notice`)

The user is entitled to: their score, the reason for denial, and the right to request their credit report. The user is NOT entitled to: raw SHAP values, exact feature values, model internals. This is by regulatory design (model confidentiality).

**Q: How do you ensure the model is not discriminatory (disparate impact)?**

Mandatory monitoring:
- Approval rate by demographic segment (where data available): gender, age group, geography
- PSI: score distribution stability across segments
- Gini coefficient by segment: does model perform equally well across groups?

Model validation before any champion promotion: run holdout dataset through model, compute disparate impact ratio (DI = approval_rate_minority / approval_rate_majority). DI < 0.8 is prima facie discrimination (4/5ths rule). Models failing this threshold are rejected regardless of Gini performance.
