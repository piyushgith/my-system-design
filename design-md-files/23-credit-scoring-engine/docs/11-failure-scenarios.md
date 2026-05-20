# 11 — Failure Scenarios: Credit Scoring Engine

---

## Objective

Enumerate concrete failure modes, their detection mechanisms, recovery procedures, RTO/RPO targets, and chaos engineering test recommendations. Focus on failures specific to the ML scoring pipeline — feature staleness, model loading failures, and the regulatory implications of serving wrong scores.

---

## Failure 1: Redis Feature Store Unavailable

**Scenario:** Primary Redis cluster crashes or network partition isolates scoring pods from Redis.

**Impact:** Feature assembly fails → score computation blocked → 503 errors to callers.

**Detection:** Redis connection timeout (configured: 100ms). Prometheus metric: `redis_connection_errors_total` alerts at > 5/minute.

**Recovery Flow:**

```
1. FeatureAssemblyService: catch Redis timeout → trigger fallback
2. Fallback: query score_history PostgreSQL for user's latest score
   SELECT * FROM score_history WHERE user_id=? ORDER BY computed_at DESC LIMIT 1
3. Return fallback score with:
   - source=FALLBACK_CACHE
   - X-Score-Source: FALLBACK_CACHE header
   - score_age_seconds: (now - computed_at).seconds
4. If no score_history record: return 503 FEATURE_STORE_UNAVAILABLE
5. Alert: PagerDuty P1 (Redis outage, scoring degraded)
```

**Caller guidance:** Loan Service MUST check `source` field. If `FALLBACK_CACHE` and `score_age_seconds > 3600` (1 hour old): escalate to manual review rather than auto-approve/decline.

**RTO:** Degraded mode (fallback scores) immediately. Redis recovery: 30–60 seconds (failover to replica). Full recovery: < 2 minutes.

**RPO:** Score computation history not impacted (stored in PostgreSQL). Feature values: potentially stale by up to 32 days (bureau) or 2 days (behavioral) depending on when features were last written.

**Chaos Test:** `kubectl exec redis-0 -- redis-cli DEBUG SLEEP 300` (simulate Redis unavailability for 5 minutes). Verify: fallback scores served, P99 latency increases to ~30ms (PostgreSQL fallback), no 503s for users with existing score history.

---

## Failure 2: ONNX Model Load Failure

**Scenario:** Model hot-reload triggered (new champion promoted), but ONNX model file in S3 is corrupted or SHA-256 hash mismatch detected.

**Impact:** Scoring pod fails to load new model → rejects model.promoted event → continues serving with old model.

**Detection:** `ModelLoadException` caught in hot-reload handler. Prometheus: `model_load_failures_total` alert at > 0.

**Recovery Flow:**

```
1. model.promoted event consumed
2. Download ONNX from S3
3. SHA-256 validation: computed hash ≠ registry hash → reject
4. Log ERROR: model load rejected {version, expected_hash, actual_hash}
5. REMAIN on current champion model (do NOT crash pod)
6. Publish model.load.failed event → Model Registry API marks model as LOAD_FAILED
7. Alert: PagerDuty P1 — model promotion failed, ML team action required
8. ML team uploads corrected model file → re-triggers promotion
```

**Critical:** pods do NOT crash on model load failure. Scoring continues with previous champion. No customer impact — just delayed model promotion.

**RTO:** Zero (no scoring interruption). Model promotion retry: manual (ML team uploads new file, risk re-approves).

**RPO:** Not applicable (no data loss).

**Chaos Test:** Corrupt ONNX model file in S3 before triggering promotion. Verify: scoring pods reject model, alert fires, old champion continues serving.

---

## Failure 3: Feature Staleness — Batch Job Failure

**Scenario:** Nightly batch feature refresh job (Spring Batch) fails midway at 3:00 AM. 2.5M of 5M users have fresh behavioral features; 2.5M still have previous day's features.

**Impact:** Batch scoring at 2 AM uses stale behavioral features for 2.5M users. Real-time scoring during business hours uses stale features (up to 24-hour-old behavioral data).

**Detection:** Spring Batch job status: `FAILED`. Prometheus: `batch_job_completion_rate` alert. PagerDuty alert at 6 AM if job hasn't completed.

**Recovery Flow:**

```
1. Spring Batch checkpoint: last committed chunk (1000-user batch)
2. On restart: resume from checkpoint (skip already-processed users)
3. Backfill: re-run feature pipeline for failed users (subset job)
4. Score cache: still valid for processed users (not invalidated by failed batch)
5. Manual decision: if batch hasn't completed by 8 AM, proceed with stale features
   (behavioral features 24 hours old — minor accuracy impact, acceptable for pre-screening)
6. For high-stakes decisions: Loan Service sets force_refresh=true
   → triggers real-time Kafka Streams feature computation
```

**RTO:** 30–60 minutes to restart and complete remaining users (if failure at 50%). Full business-hours impact: minimal (stale behavioral features, not missing bureau features).

**RPO:** Feature data: up to 24 hours stale for 50% of users. Score accuracy: ~2-3% degradation for users with significant behavioral changes overnight (acceptable, not regulatory violation).

**Chaos Test:** Inject `OutOfMemoryError` into batch Spring Batch executor at chunk 2500/5000. Verify: job fails, checkpoint persisted, restart resumes from chunk 2501.

---

## Failure 4: Champion/Challenger Routing Inconsistency

**Scenario:** Model registry database has inconsistent state — two models both have role=CHAMPION for the same product type after a botched promotion transaction.

**Impact:** Routing reads two CHAMPION models → unpredictable model selection → different scoring behavior than expected.

**Detection:** `ModelRegistryService.getModels()` returns > 1 CHAMPION per product_type → `InvalidModelConfigurationException`. Prometheus: `model_config_errors_total` alert at > 0.

**Prevention:** `CREATE UNIQUE INDEX idx_model_champion_product ON model_registry(product_types, role) WHERE role = 'CHAMPION'` — PostgreSQL constraint prevents two CHAMPIONs at DB level.

**Recovery Flow:**

```
1. Alert fires: duplicate champion detected (should not be possible due to DB constraint)
2. If constraint somehow bypassed: scoring engine defaults to MOST RECENTLY DEPLOYED model
3. Model Registry API: emergency endpoint POST /models/{v}/force-retire to resolve conflict
4. Audit: determine how two CHAMPIONs were created (likely concurrent promotion race)
5. Postmortem: add advisory locking to promotion endpoint
```

**RTO:** Scoring continues (defaults to latest model). Resolution: < 10 minutes.

---

## Failure 5: Kafka Consumer Lag — Score Audit Writer

**Scenario:** PostgreSQL primary is slow (disk IOPS throttled on RDS). Score audit writer consumer falls 30+ minutes behind. Score computations are happening but not being persisted to `score_history`.

**Impact:** Audit gap — cannot reproduce score decisions for this window. Regulatory risk if users dispute decisions made during the gap.

**Detection:** Kafka consumer group lag metric: `score-audit-writer` lag > 10,000 messages. Alert threshold: lag > 30 minutes worth of messages.

**Recovery Flow:**

```
1. Alert fires: score audit writer lagging
2. Triage: is PostgreSQL primary healthy? check RDS CloudWatch (IOPS, CPU)
3. If IOPS throttled: upgrade RDS instance class or enable gp3 with higher provisioned IOPS
4. Score audit writer: configured with retry and backpressure (blocks if DB insert fails)
5. On recovery: consumer catches up automatically (no message loss — Kafka retention = 30 days)
6. Postmortem: if regulatory audit requested for lagging window, reconstruct from score cache events
```

**RPO:** Kafka messages preserved for 30 days. No scores lost — audit writer will eventually catch up. Gap in PostgreSQL record is temporary.

---

## Failure 6: Bureau Data Consent Expiry at Scale

**Scenario:** Consent Management Service pushes bulk consent expiry notification (100K users' consents expire simultaneously after 6 months).

**Impact:** 100K score requests within 1 hour receive degraded "no-bureau" scores (bureau features excluded). Loan approval rates may drop temporarily.

**Detection:** Consent cache miss rate spikes. `is_thin_file=true` rate in score responses spikes. Alert: `no_bureau_score_rate > 20%` for any 5-minute window.

**Recovery Flow:**

```
1. Loan Service detects score with X-Bureau-Consent-Status: EXPIRED
2. Triggers re-consent flow for user (push notification / in-app prompt)
3. On re-consent: Consent Service publishes consent.granted event
4. Consent cache updated: consent:{user_id}:bureau TTL refreshed
5. Next score request: full bureau features assembled
6. Bulk consent expiry prevention: stagger consent expiry dates at signup
   (don't use same expiry for all users onboarded in the same week)
```

**Mitigation:** proactively notify users 7 days before consent expiry (Notification Service consuming consent.expiry_approaching events).

---

## Failure 7: Model Inference Latency Spike

**Scenario:** ONNX model inference latency spikes from 3ms to 50ms due to JVM GC pause or CPU contention from concurrent batch scoring.

**Impact:** P99 scoring latency exceeds 200ms SLA. Loan Service receives timeout responses.

**Detection:** Prometheus: `model_inference_duration_p99 > 50ms`. JVM GC log: stop-the-world GC > 20ms.

**Recovery Flow:**

```
1. Horizontal Pod Autoscaler: scale scoring pods 3 → 12 (1 minute to provision)
2. Batch scoring job throttled: reduce concurrent chunk processing to 2 (from 5)
3. JVM GC tuning: switch to G1GC with MaxGCPauseMillis=10, increase heap size
4. If persistent: isolate batch scoring to dedicated node pool (separate from real-time scoring)
5. Circuit breaker: if P99 > 500ms for 30 seconds → return 503 to callers (backpressure)
```

**RTO:** < 2 minutes (HPA scale-out). Immediate degradation mode: return cached scores during spike.

---

## Failure 8: New Model Causes Score Regression

**Scenario:** New champion model (v2.4.0) is promoted and starts scoring users. Within 2 hours, business team notices approval rate dropped from 65% to 45% — model is too conservative.

**Impact:** 20% of eligible users denied credit. Revenue impact. Regulatory risk (disparate impact).

**Detection:** Business dashboard: approval_rate metric drops > 10% from baseline (rolling 2-hour window). Alert: PagerDuty P1 to risk team.

**Recovery Flow:**

```
1. Risk team: POST /api/v1/models/xgb-v2.3.1/promote (rollback to previous champion)
   {approval_token: <emergency_rollback_token>, promotion_reason: "Score regression detected"}
2. Model registry: xgb-v2.3.1 re-promoted to CHAMPION, v2.4.0 retired
3. All scoring pods: hot-reload old champion (< 30 seconds)
4. Score cache: TTL expiry clears v2.4.0 scores (5-minute natural expiry)
5. Postmortem: re-run v2.4.0 backtesting on holdout data to identify regression source
```

**Emergency rollback process:** model registry maintains "last stable champion" reference. Risk team has pre-approved rollback tokens for the previous champion. No engineering deployment required — API call only.

**RTO:** < 5 minutes (API call → hot-reload → cache TTL expiry).

---

## Failure Summary

| Failure | Detection | RTO | RPO | Key Mitigation |
|---|---|---|---|---|
| Redis feature store down | Redis timeout > 100ms | 2 min (fallback immediate) | 0 (score_history intact) | PostgreSQL fallback, PagerDuty P1 |
| ONNX model load failure | SHA-256 mismatch | 0 (old model continues) | 0 | Reject + stay on old model |
| Batch job failure | Job status FAILED | 30–60 min (restart) | 24h feature staleness | Spring Batch checkpoint |
| Dual champion in registry | DB constraint violation | < 10 min | 0 | UNIQUE INDEX prevents |
| Score audit writer lag | Consumer lag > 10K | Temporary (auto-catchup) | Temporary gap | Kafka 30-day retention |
| Bulk consent expiry | no_bureau_score_rate spike | Minutes (re-consent flow) | 0 | Stagger expiry dates |
| Model inference latency spike | P99 > 50ms | 2 min (HPA scale-out) | 0 | Batch isolation, GC tuning |
| Model score regression | Approval rate drop > 10% | 5 min (emergency rollback) | 0 | Pre-approved rollback tokens |

---

## Interview Discussion Points

- **What is the regulatory impact of an audit gap (score audit writer lag)?** Audit gap means some loan decisions cannot be reproduced. RBI Credit Information Companies regulations require score records to be available for 7 years. A 30-minute gap is operationally serious but not catastrophic — Kafka preserves the messages, the writer catches up. A permanent loss (Kafka topic deleted, retention expired) would be a regulatory violation. Mitigation: never decrease Kafka retention below 30 days, and alert on consumer lag before the window becomes unrecoverable
- **How do you handle the case where a user's score changes during loan processing?** Loan origination is a multi-step process (submission → verification → credit check → approval). The credit score is fetched at "credit check" step and stored in the loan application record. `credit.score.significant_change` events are consumed by the Loan Service. If a significant change (> 20 points) occurs while the application is in "under review" (not yet approved), the Loan Service automatically re-triggers the credit check step with the latest score. If the change occurs after approval (disbursement), the score at approval time is the governing score for the loan contract
- **How do you test model regression before production promotion?** Shadow mode: new model deployed with `role=SHADOW` (0% caller traffic). Scoring engine computes shadow scores alongside champion scores for every real-time request. Shadow scores stored in `score_history` with `model_role=SHADOW`. Data science team runs 7-day shadow analysis: compare shadow score distribution against champion. If distributions match expected improvement → promote to CHALLENGER (10%). After 45 days challenger data → promote to CHAMPION
