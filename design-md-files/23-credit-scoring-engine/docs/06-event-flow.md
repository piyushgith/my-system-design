# 06 — Event Flow: Credit Scoring Engine

---

## Objective

Document the complete event flows for real-time scoring, batch scoring, feature pipeline updates, score change detection, and champion-challenger routing. Include timing budgets and failure handling at each step.

---

## Flow 1: Real-Time Score Request (Cache Hit)

```mermaid
sequenceDiagram
    participant LOAN as Loan Service
    participant API as Scoring API
    participant CACHE as Score Cache (Redis)

    LOAN->>API: POST /api/v1/scores {user_id, product_type, force_refresh=false}
    API->>CACHE: GET score:{user_id}:{product_type}
    CACHE-->>API: {score: 780, band: EXCELLENT, computed_at: T-2min}
    Note over API: TTL > 0, age < 5min → serve from cache
    API-->>LOAN: 200 OK {score: 780, source: CACHE} [< 2ms total]
```

**Timing budget:** Redis GET = 0.5ms, JSON deserialization = 0.2ms, response = < 2ms total.

No database read. No model inference. No Kafka events emitted on cache hit.

---

## Flow 2: Real-Time Score Request (Cache Miss — Full Inference)

```mermaid
sequenceDiagram
    participant LOAN as Loan Service
    participant API as Scoring API
    participant CACHE as Score Cache (Redis)
    participant ROUTER as Champion/Challenger Router
    participant FEAT as Feature Assembly Service
    participant REDIS as Feature Store (Redis)
    participant MODEL as Model Inference (ONNX)
    participant REASON as Reason Code Service
    participant AUDIT as Score Audit (async)
    participant DB as PostgreSQL (async)
    participant KAFKA as Kafka (async)

    LOAN->>API: POST /api/v1/scores {user_id, product_type}
    API->>CACHE: GET score:{user_id}:{product_type}
    CACHE-->>API: nil (miss)

    API->>ROUTER: route(user_id) → ModelRegistration
    Note over ROUTER: user_id hash % 100 → champion (90%) or challenger (10%)
    ROUTER-->>API: ModelRegistration {model_version: xgb-v2.3.1, role: CHAMPION}

    API->>FEAT: assembleFeatures(user_id, model_version)
    FEAT->>REDIS: MGET [feature:user_id:bureau.*, feature:user_id:behavior.*, ...]
    REDIS-->>FEAT: [720, 0, 0.35, 45, 85000, false, ...] (15-20 values)
    Note over FEAT: validate completeness, apply defaults for missing features
    FEAT-->>API: FeatureVector [15-20 ordered values]

    API->>MODEL: predict(featureVector, model_version)
    Note over MODEL: ONNX in-process inference, XGBoost predict_proba
    MODEL-->>API: {raw_pd: 0.082, shap_values: {...}}

    API->>REASON: generateReasonCodes(shap_values, featureVector)
    REASON-->>API: [ReasonCode{03, NEGATIVE, rank:1}, ReasonCode{02, NEGATIVE, rank:2}]

    API->>CACHE: SET score:{user_id}:{product_type} TTL=300 (async, non-blocking)
    API->>AUDIT: persistScore(CreditScoreRequest) (async, non-blocking)
    AUDIT->>DB: INSERT score_history (...) (async)
    AUDIT->>KAFKA: PUBLISH credit.score.computed (async)

    API-->>LOAN: 200 OK {score: 750, reason_codes, model_version, computed_at} [~15ms]
```

**Timing budget:**

| Step | Budget |
|---|---|
| Score cache miss check | 0.5ms |
| Champion/challenger routing (in-memory) | < 0.1ms |
| Feature assembly — Redis MGET | 3–5ms |
| ONNX model inference | 1–3ms |
| Reason code generation | 0.5ms |
| JSON serialization + network | 2–5ms |
| **Total P50** | **~10ms** |
| **Total P99** | **< 200ms** |

Async paths (score cache write, DB insert, Kafka publish) do NOT block the response.

---

## Flow 3: Feature Update — Bureau Data Refreshed

```mermaid
sequenceDiagram
    participant CIBIL as CIBIL API
    participant BUREAU_SVC as Bureau Integration Service
    participant KAFKA as Kafka
    participant CONSUMER as Bureau Feature Consumer
    participant FEAT_WRITER as Feature Store Writer
    participant REDIS as Feature Store (Redis)
    participant SCORE_CACHE as Score Cache (Redis)

    Note over CIBIL,BUREAU_SVC: Triggered by: periodic refresh (30 days) or new bureau pull consent
    BUREAU_SVC->>CIBIL: FetchBureauReport(pan_number, consent_token)
    CIBIL-->>BUREAU_SVC: BureauReport XML (CIBILTUScore, DPDs, utilization, ...)

    BUREAU_SVC->>BUREAU_SVC: ACL: map CIBIL fields → canonical feature names
    Note over BUREAU_SVC: BureauDataAdapter.translate(report) → Map<FeatureName, FeatureValue>

    BUREAU_SVC->>KAFKA: PUBLISH bureau.data.refreshed {user_id, features: {...}, report_s3_path, refreshed_at}

    KAFKA->>CONSUMER: CONSUME bureau.data.refreshed
    CONSUMER->>FEAT_WRITER: updateBureauFeatures(user_id, features, refreshed_at)
    FEAT_WRITER->>REDIS: MSET [feature:user_id:bureau.cibil_score = 720, feature:user_id:bureau.dpd_last_6m = 0, ...]
    FEAT_WRITER->>REDIS: SET feature:user_id:meta.bureau_as_of = "2024-01-01T00:00:00Z"
    Note over REDIS: TTL = 32 days per bureau feature key
    FEAT_WRITER->>SCORE_CACHE: DEL score:{user_id}:* (invalidate cached scores for this user)
    Note over SCORE_CACHE: Score cache invalidated — next request gets fresh score from new bureau features
    FEAT_WRITER->>KAFKA: PUBLISH feature.profile.updated {user_id, feature_group: BUREAU, updated_at}
```

**Lag budget:** CIBIL API call (async, non-blocking from scoring path) → Kafka message → consumer → Redis update. Total feature store update lag: < 60 seconds from bureau data received.

---

## Flow 4: Real-Time Feature Update — Transaction Event

```mermaid
sequenceDiagram
    participant LEDGER as Ledger Service
    participant KAFKA as Kafka
    participant STREAMS as Kafka Streams (Feature Pipeline)
    participant REDIS as Feature Store (Redis)

    LEDGER->>KAFKA: PUBLISH transaction.posted {user_id, amount, type: EMI_PAYMENT, posted_at}

    KAFKA->>STREAMS: CONSUME transaction.posted
    Note over STREAMS: Sliding window aggregate over last 30 days
    STREAMS->>STREAMS: UPDATE window: emi_count_last_30d += 1, avg_monthly_credit = recalculate
    STREAMS->>REDIS: SET feature:user_id:behavior.emi_count_last_30d = 5
    STREAMS->>REDIS: SET feature:user_id:behavior.avg_monthly_credit = 85000
    STREAMS->>REDIS: SET feature:user_id:performance.current_emi_dpd = 0
    Note over REDIS: Real-time features: TTL = 24 hours (refreshed by daily batch if no events)

    Note over STREAMS: Score cache NOT invalidated for behavioral features (cost tradeoff)
    Note over STREAMS: force_refresh=true on next loan application will pick up fresh features
```

**Freshness SLA:** transaction event → behavioral feature update in Redis: < 60 seconds.

**Tradeoff:** score cache is NOT invalidated on every transaction (too expensive at 50 RPS × many transactions). The 5-minute TTL naturally expires stale scores. For final loan decision: caller sets `force_refresh=true`.

---

## Flow 5: Batch Scoring (Nightly Job)

```mermaid
sequenceDiagram
    participant SCHEDULER as Job Scheduler (Kubernetes CronJob)
    participant BATCH as Spring Batch Job
    participant REDIS as Feature Store (Redis)
    participant MODEL as Model Inference (ONNX)
    participant DB as PostgreSQL
    participant KAFKA as Kafka

    Note over SCHEDULER: Trigger: 2:00 AM daily
    SCHEDULER->>BATCH: START batch_scoring_job {model_version, product_type, job_id}
    BATCH->>DB: INSERT batch_scoring_jobs (status=RUNNING, job_id, started_at)

    loop For each user chunk (1000 users per chunk)
        BATCH->>REDIS: MGET features for 1000 users (pipeline: 1000 MGET calls)
        REDIS-->>BATCH: Feature vectors for 1000 users
        Note over BATCH: Skip users with missing bureau features (thin-file → special handling)
        BATCH->>MODEL: predict_batch(featureVectors[1000])
        MODEL-->>BATCH: [{raw_pd, score, shap_values}, ...] × 1000
        BATCH->>DB: COPY score_history (1000 rows) — bulk insert
        BATCH->>DB: UPDATE batch_scoring_jobs SET scored_users += 1000
    end

    BATCH->>DB: UPDATE batch_scoring_jobs SET status=COMPLETED, completed_at=now()
    BATCH->>KAFKA: PUBLISH batch.scoring.completed {job_id, model_version, total_scored, duration}

    Note over BATCH: SHAP values NOT computed in batch (2 GB/night storage cost)
    Note over BATCH: Score cache NOT populated from batch (stale immediately for individual callers)
```

**Scale:** 5M users / 1000 per chunk = 5000 chunks. At 2 chunks/second = 2500 seconds (~42 minutes). Target: complete before 6:00 AM.

**Parallelism:** 10 Spring Batch partitions (5 parallel workers, 2 chunks each in flight). Estimated runtime: < 15 minutes.

---

## Flow 6: Score Change Detection and Event Publishing

```mermaid
sequenceDiagram
    participant AUDIT as Score Audit Service
    participant DB as PostgreSQL
    participant DETECTOR as Score Change Detector
    participant KAFKA as Kafka
    participant LOAN as Loan Service
    participant RISK as Risk Engine

    Note over AUDIT: Triggered after every score computation (real-time or batch)
    AUDIT->>DB: SELECT score FROM score_history WHERE user_id=? ORDER BY computed_at DESC LIMIT 1
    DB-->>AUDIT: previous_score = 730

    AUDIT->>DETECTOR: detectChange(user_id, new_score=680, previous_score=730)
    Note over DETECTOR: delta = -50 points → isSignificantChange (threshold: ±20 points)
    DETECTOR-->>AUDIT: ScoreChangedEvent {user_id, old_score: 730, new_score: 680, delta: -50}

    AUDIT->>KAFKA: PUBLISH credit.score.significant_change {user_id, old_score, new_score, delta, model_version, computed_at}

    KAFKA->>LOAN: CONSUME credit.score.significant_change
    Note over LOAN: Loan Service re-evaluates any pending application for this user
    KAFKA->>RISK: CONSUME credit.score.significant_change
    Note over RISK: Risk Engine flags large negative score drops for manual review
```

**Threshold rationale:** ±20 points chosen because:
- < 20 points: normal model variance, credit line fluctuation — not actionable
- ≥ 20 points: meaningful change (e.g., new DPD, resolved collection) — upstream decisions should be re-evaluated

---

## Flow 7: Champion-Challenger Routing

```mermaid
sequenceDiagram
    participant API as Scoring API
    participant ROUTER as Champion/Challenger Router
    participant REGISTRY as Model Registry (in-memory)
    participant CHAMPION as Champion Model (ONNX)
    participant CHALLENGER as Challenger Model (ONNX)
    participant DB as PostgreSQL

    API->>ROUTER: route(user_id="usr_abc123", product_type=PERSONAL_LOAN)
    ROUTER->>REGISTRY: getModels(product_type=PERSONAL_LOAN)
    REGISTRY-->>ROUTER: [{role:CHAMPION, version:xgb-v2.3.1}, {role:CHALLENGER, version:xgb-v2.4.0, traffic_pct:10}]

    Note over ROUTER: hash(user_id) % 100 = 37 → 37 < 90 → CHAMPION
    ROUTER-->>API: model=xgb-v2.3.1, role=CHAMPION

    API->>CHAMPION: predict(featureVector)
    CHAMPION-->>API: {raw_pd: 0.08, score: 750}

    Note over API: Record model_role=CHAMPION in score_history
    API->>DB: INSERT score_history (model_version=xgb-v2.3.1, model_role=CHAMPION, ...)
```

**For challenger traffic (hash % 100 ≥ 90):**
- Route to `xgb-v2.4.0`, record `model_role=CHALLENGER`
- Caller receives identical response format — does not know it received challenger model
- Both champion and challenger scores independently persisted in `score_history`

**Shadow mode:** `model_role=SHADOW` — compute score but do NOT return to caller. Used for new model validation before challenger traffic is enabled.

---

## Flow 8: Model Hot-Reload

```mermaid
sequenceDiagram
    participant RISK_TEAM as Risk Team
    participant REGISTRY_API as Model Registry API
    participant DB as PostgreSQL
    participant KAFKA as Kafka
    participant LOADER as Model Loader (all pods)
    participant S3 as S3

    RISK_TEAM->>REGISTRY_API: POST /api/v1/models/xgb-v2.4.0/promote {approval_token}
    REGISTRY_API->>DB: UPDATE model_registry SET role=CHAMPION WHERE version=xgb-v2.4.0
    REGISTRY_API->>DB: UPDATE model_registry SET role=RETIRED WHERE version=xgb-v2.3.1
    DB-->>REGISTRY_API: success

    REGISTRY_API->>KAFKA: PUBLISH model.promoted {new_champion: xgb-v2.4.0, retired: xgb-v2.3.1}

    KAFKA->>LOADER: CONSUME model.promoted (all scoring engine pods)
    LOADER->>S3: GetObject(s3://models/xgb-v2.4.0.onnx)
    S3-->>LOADER: ONNX model bytes
    LOADER->>LOADER: Load new OnnxSession, warm up (predict on synthetic feature vector)
    Note over LOADER: Atomic swap: new session replaces old session (no downtime)
    LOADER->>DB: Log model_load_event (pod_id, model_version, loaded_at)

    Note over LOADER: Old model session GC'd after all in-flight requests complete (graceful drain ~5s)
```

**No pod restart required.** Hot-reload via Kafka event → S3 fetch → in-memory session swap. All pods load independently.

---

## Flow Timing Summary

| Flow | P50 Latency | P99 Latency | Async? |
|---|---|---|---|
| Real-time score (cache hit) | < 2ms | < 5ms | No |
| Real-time score (cache miss) | ~10ms | < 200ms | Score write async |
| Bureau feature update | ~60s end-to-end | ~120s | Yes (Kafka) |
| Transaction feature update | ~30s | ~60s | Yes (Kafka Streams) |
| Batch scoring (5M users) | 42 min single / 15 min parallel | — | Yes (CronJob) |
| Score change detection | < 5ms | < 20ms | Yes (Kafka) |
| Model hot-reload | ~15s per pod | ~30s | Yes (Kafka) |

---

## Interview Discussion Points

- **Why is score cache NOT invalidated on every transaction event?** At 50 RPS scoring and millions of users transacting daily, invalidating the score cache on every transaction would flood the scoring path with cache misses. The 5-minute TTL is a deliberate staleness tolerance. For high-value decisions (final loan submission), `force_refresh=true` bypasses cache and reads fresh features
- **What happens if the batch scoring job fails midway?** Spring Batch checkpoints at the chunk level (1000 users). Restart resumes from the last committed chunk. `scored_users` counter tracks progress. Users scored in the failed run are skipped (idempotent: upsert by request_id). The job failure triggers a PagerDuty alert — must complete before business hours
- **How are champion and challenger scores compared?** `score_history` stores `model_role` per row. After 45 days: `SELECT model_role, model_version, AVG(raw_pd), COUNT(*) FROM score_history WHERE computed_at > NOW() - INTERVAL '45 days' GROUP BY model_role, model_version` — then joined against loan performance data (did the user actually default?). Gini coefficient and KS statistic compared
- **Why use Kafka Streams for behavioral features instead of batch?** Loan EMI payment at 11:45 PM should be reflected before the 2 AM batch scoring run. Real-time Kafka Streams updates `behavior.current_emi_dpd = 0` within 60 seconds. Without real-time features, a user who just paid off a DPD would be batch-scored with stale negative signals — materially affecting their credit limit decision next morning
