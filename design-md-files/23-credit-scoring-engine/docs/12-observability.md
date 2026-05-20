# 12 — Observability: Credit Scoring Engine

---

## Objective

Define the full observability stack for the credit scoring engine: structured logging, distributed tracing, Prometheus metrics, SLI/SLO definitions, and alerting. Special consideration for ML-specific observability: model drift detection, feature freshness monitoring, and regulatory compliance reporting.

---

## Logging Strategy

### Structured Log Format

All logs in JSON via Logback + Logstash encoder:

```json
{
  "timestamp": "2024-01-15T10:30:00.015Z",
  "level": "INFO",
  "service": "credit-scoring-engine",
  "pod": "scoring-engine-7f4b8c9d-xyz",
  "trace_id": "4bf92f3577b34da6",
  "span_id": "00f067aa0ba902b7",
  "request_id": "req_loan_abc123",
  "user_id": "usr_abc123",  ← pseudonym only, no PII
  "product_type": "PERSONAL_LOAN",
  "event": "score_computed",
  "score": 750,
  "model_version": "xgb-v2.3.1",
  "model_role": "CHAMPION",
  "source": "REAL_TIME",
  "latency_ms": 12,
  "cache_hit": false
}
```

**PII Policy:** `user_id` is a pseudonymous UUID. No name, PAN, Aadhaar, phone, or email in any log line. `feature_snapshot` and `shap_values` never logged (too large, contains financial signals). Violations detected by log scanning rule in SIEM.

### Log Levels

| Level | Usage |
|---|---|
| `ERROR` | Score computation failure, model load failure, DB unreachable |
| `WARN` | Redis fallback triggered, feature missing (using default), consent expired |
| `INFO` | Score computed (structured event), model hot-reload complete, batch job started/completed |
| `DEBUG` | Feature assembly details, champion/challenger routing decision |

`DEBUG` disabled in production (feature vector contains financial signals). Enabled per-request via header `X-Debug-Scoring: true` with `scoring:admin` scope only.

---

## Distributed Tracing

OpenTelemetry SDK + Jaeger (or AWS X-Ray in cloud).

### Trace Propagation

```
Loan Service → [HTTP: traceparent header] → Scoring API → [in-process] → Feature Assembly
                                                                       → [Redis span] → Feature Store
                                                                       → [in-process] → ONNX Inference
                                                                       → [async Kafka] → Score Audit Writer
```

Kafka messages include W3C `traceparent` header in Kafka message headers → Score Audit consumer continues the trace.

### Key Spans

| Span Name | Tags | Alert On |
|---|---|---|
| `scoring.compute` | user_id, product_type, model_version, source | duration > 200ms |
| `feature.assembly` | user_id, feature_count, thin_file | duration > 10ms |
| `redis.mget.features` | key_count, hit_count | duration > 5ms |
| `onnx.inference` | model_version, pd_score | duration > 10ms |
| `reason.code.generation` | reason_count | duration > 2ms |
| `score.cache.write` | ttl | duration > 2ms |
| `score_history.insert` (async) | record_count | duration > 100ms |

**Sampling rate:** 5% for successful requests. 100% for errors, P99 latency outliers, and requests from `scoring:admin` scope.

---

## Prometheus Metrics

### Scoring Engine Metrics

```
# Score computation counter
credit_scoring_computations_total{product_type, model_version, model_role, source, result}
→ Alert: error rate > 0.1% for 5min

# Score computation latency
credit_scoring_duration_seconds{product_type, model_version, quantile}
→ Alert: P99 > 200ms

# Cache hit rate
credit_scoring_cache_hit_ratio{product_type}
→ Alert: cache_hit_ratio < 0.6 (sustained miss → ONNX overloaded)

# Model inference duration
credit_scoring_onnx_inference_duration_seconds{model_version, quantile}
→ Alert: P99 > 50ms

# Feature assembly: missing features (thin-file rate)
credit_scoring_thin_file_total{product_type}
→ Monitor: trend increase = bureau data pipeline issue

# Score distribution by band
credit_scoring_score_band_total{band, product_type, model_role}
→ Alert: EXCELLENT/GOOD band rate drops > 10% = model regression signal

# Consent check failures
credit_scoring_consent_denied_total{reason}
→ Alert: consent_denied_total spikes = Consent Service issue or consent mass-expiry
```

### Feature Pipeline Metrics

```
# Feature refresh lag
feature_pipeline_lag_seconds{feature_group}
→ Alert: bureau lag > 300s, behavioral lag > 300s

# Feature store write failures
feature_store_write_failures_total{feature_group}
→ Alert: any failure > 0

# Redis key TTL approaching expiry (before expected refresh)
feature_store_keys_expiring_soon_total{feature_group}
→ Alert: > 10K keys expiring before next scheduled refresh = pipeline at risk
```

### Model Registry Metrics

```
# Model version serving distribution
credit_scoring_model_traffic_pct{model_version, model_role}
→ Dashboard: champion vs challenger traffic split visualization

# Model hot-reload events
credit_scoring_model_reloads_total{model_version, result}
→ Alert: reload_result=FAILED > 0

# Model load latency
credit_scoring_model_load_duration_seconds{model_version}
→ Monitor: model file size growing → load time increasing
```

---

## ML-Specific Observability

### Feature Drift Detection

Features that drift significantly from training distribution degrade model accuracy.

```
# Feature value distribution monitoring (sampled 1% of requests)
feature_value_histogram{feature_name, bucket}

Drift alert: if bureau.cibil_score P50 changes by > 30 points week-over-week
            → indicates change in bureau data quality or population mix
```

**Implementation:** Kafka Streams sampling job reads 1% of score requests, extracts feature vectors, computes rolling statistics, publishes to Prometheus via custom exporter. Data Science team reviews weekly feature drift report.

### Model Performance Monitoring (PSI — Population Stability Index)

**Problem:** champion model trained on historical data. Real population shifts over time → model calibration degrades.

**Detection:**
- Weekly: compare current week's `raw_pd` distribution vs model training distribution
- PSI > 0.2: model degradation — trigger retraining
- Approval rate trend: if approval_rate changes > 10% with no business policy change → model drift

**Implementation:** Spark job reads `score_history` weekly, computes PSI per score decile, publishes to Grafana dashboard.

### Champion vs Challenger Performance Dashboard

```sql
-- Run weekly, compare model performance
SELECT
  model_role,
  model_version,
  COUNT(*) as scored_applications,
  AVG(score) as avg_score,
  AVG(raw_pd) as avg_pd,
  PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY score) as median_score
FROM score_history
WHERE computed_at > NOW() - INTERVAL '30 days'
  AND product_type = 'PERSONAL_LOAN'
GROUP BY model_role, model_version;
```

Joined against loan performance data (from Loan Performance Service) after 6–12 months to compute actual default rates.

---

## SLI / SLO Definitions

### SLO 1: Real-Time Scoring Latency

```
SLI: percentage of real-time scoring requests with P99 latency < 200ms
SLO: 99.5% of requests within 200ms (rolling 28 days)
Error budget: 0.5% = ~3.5 hours/month of breaches at current RPS
Alert: burn rate > 2× (fast burn) over 1 hour → P1 page
```

### SLO 2: Scoring Availability

```
SLI: percentage of scoring requests returning 2xx or documented 4xx (MISSING_CONSENT, INVALID_PRODUCT_TYPE)
SLO: 99.9% success rate (excluding client errors)
Error budget: 0.1% = ~43 minutes/month
Alert: 5xx rate > 1% for 5 minutes → P1 page
```

### SLO 3: Score Audit Completeness

```
SLI: percentage of score computations persisted to score_history within 5 minutes
SLO: 99.9% of scores persisted within 5 minutes
Monitoring: Kafka consumer lag → estimate time-to-persist
Alert: lag implies > 5 minutes pending → P2 alert
```

### SLO 4: Feature Freshness

```
SLI: percentage of real-time scoring requests where bureau features are < 32 days old
SLO: 99% of requests with fresh bureau features
Monitoring: feature:user_id:meta.bureau_as_of → age > 32 days = stale
Alert: > 1% users with stale bureau features → data pipeline investigation
```

### SLO 5: Batch Scoring Completion

```
SLI: percentage of nightly batch jobs completing before 6 AM
SLO: 99% batch completion by 6 AM (allows 1 failure/~100 nights)
Alert: batch job not completed by 5 AM → P1 (1 hour buffer before business hours)
```

---

## Dashboards

### Operations Dashboard (Real-Time)

- Score computation rate (RPS) by product_type
- Cache hit rate (target > 80%)
- P50/P95/P99 latency (last 1 hour)
- Error rate by error code
- Active scoring pods count (HPA state)
- Redis feature store connection health

### Model Performance Dashboard (Daily)

- Champion vs challenger score distribution (histogram overlay)
- Approval rate by model_role (champion vs challenger)
- Thin-file rate trend (weekly)
- Feature freshness: percentage of users with stale features by group

### Regulatory Compliance Dashboard (Weekly)

- Score band distribution (EXCELLENT/GOOD/FAIR/POOR/VERY_POOR) by product_type
- Adverse action reason code frequency (which codes most frequently cited)
- Consent usage: bureau vs no-bureau score volume
- Score audit completeness: gaps in score_history (missing request_ids)

---

## Alerting Runbook Highlights

| Alert | Severity | First Response |
|---|---|---|
| P99 latency > 200ms for 5min | P1 | Check Redis MGET latency, ONNX inference time, HPA pod count |
| 5xx rate > 1% | P1 | Check Redis connectivity, PostgreSQL health, model loaded? |
| model_load_failures_total > 0 | P1 | Check S3 model file integrity, SHA-256 match |
| Batch scoring not complete by 5 AM | P1 | Check Spring Batch job status, Redis cluster health |
| feature_store_write_failures > 0 | P2 | Check Kafka consumer lag, Redis memory usage |
| Approval rate drops > 10% | P1 | Check model version served, possible model regression |
| Kafka consumer lag > 30min (audit writer) | P2 | Check PostgreSQL IOPS, audit writer pod health |

---

## Interview Discussion Points

- **How do you detect model drift without labeled training data in production?** Two signals: (1) input drift — feature distribution changes (PSI on bureau.cibil_score distribution week-over-week); (2) output drift — score distribution changes (approval rate shifts without policy change). Actual default rate comparison requires 6–12 month lag (must wait to see who defaults). Proxy signals: early delinquency rate (DPD 30+ at 3 months) can serve as 3-month leading indicator
- **What is the difference between a score SLO and a model SLO?** Score SLO is about system reliability (is the API responding, is it fast?). Model SLO is about prediction quality (is the model well-calibrated, is the approval rate stable?). You need both. A scoring engine can have 99.99% uptime SLO while the model is drifting badly — both need monitoring. Model SLOs are owned by data science; system SLOs are owned by engineering
- **Why sample only 1% of requests for feature drift monitoring?** Storing full feature vectors for every request is expensive (15–20 features × 8 bytes × 500K requests/day = ~60 MB/day — manageable, but drift signals don't require every request). A 1% sample (5000 requests/day) gives sufficient statistical power to detect distribution shifts with 95% confidence for common features. Reservoir sampling ensures unbiased representation
- **How do you measure the impact of challenger model before it becomes champion?** After 45 days with 10% challenger traffic: extract `score_history` for challenger-served requests. Join against Loan Service data: was a loan approved? Was there an early DPD at 90 days? Compute Gini coefficient (discrimination power) and calibration error (is raw_pd calibrated to actual default rate?). Statistical significance test (paired t-test on Gini). If Gini improvement > 2% with p < 0.05 → recommend promotion
