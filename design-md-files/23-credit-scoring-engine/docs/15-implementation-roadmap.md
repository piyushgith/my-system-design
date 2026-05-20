# 15 — Implementation Roadmap: Credit Scoring Engine

---

## Objective

Define a phased implementation plan from MVP to production-scale system. Each phase has clear scope, team size, architecture state, and risks. The roadmap prioritizes regulatory compliance and accuracy over feature richness.

---

## Phase 0: Foundation (Weeks 1–4, 2 Engineers)

**Goal:** Working scoring pipeline with a single model, no ML — validate the infrastructure.

### Scope

- PostgreSQL schema: `score_history`, `model_registry`, `feature_definitions`
- Redis: feature store schema (flat key-value, manually seeded)
- Scoring Engine: Spring Boot app with ONNX Runtime dependency
- API: `POST /api/v1/scores` (REST only, no gRPC), `GET /api/v1/scores/{id}`
- Manual feature loading: CSV import script for bureau features (no Kafka pipeline yet)
- Champion model: single ONNX model loaded from S3 on startup (no hot-reload)
- Reason codes: static mapping from feature name to reason code string
- Score storage: synchronous insert into score_history (not async yet)

### Architecture State

```
[Loan Service (mock)] → [Scoring API] → [Redis (manually seeded)]
                                      → [ONNX Model (local file)]
                                      → [PostgreSQL score_history]
```

### Definition of Done

- Score computed correctly for test users with known bureau data (compare against CIBIL score)
- `score_history` record matches the score returned in API response (audit trail verified)
- Feature snapshot stored with each score (reproducibility verified: re-run score with same snapshot → same result)

### Risks

- ONNX model conversion from XGBoost: ensure `predict_proba` output format matches expectation
- Java ONNX Runtime dependency management (native library loading)

---

## MVP (Weeks 5–10, 3 Engineers)

**Goal:** Production-ready scoring for one product type (Personal Loan) with real feature pipeline.

### Scope

- Feature pipeline: Kafka consumer for `bureau.data.refreshed` events (Bureau Integration Service must be live)
- Feature pipeline: Kafka Streams consumer for `transaction.posted` (behavioral features)
- Score cache: Redis score cache with 5-minute TTL
- Idempotency: Redis-backed + score_history PRIMARY KEY backstop
- Async score storage: Kafka `credit.score.computed` → score audit writer consumer → PostgreSQL
- Champion/challenger router: in-memory routing by user_id hash (10% challenger traffic)
- gRPC API: add gRPC endpoint alongside REST (Loan Service migrates to gRPC)
- Consent check: integration with Consent Management Service
- Reason codes: SHAP-based (onnxruntime returns SHAP values for XGBoost ONNX)
- Basic observability: Prometheus metrics, structured logging, Grafana dashboard

### Architecture State

```
[Bureau Integration] → [Kafka: bureau.data.refreshed] → [Feature Pipeline]
[Ledger Service]     → [Kafka: transaction.posted]    → [Feature Pipeline]
                                                               ↓
[Loan Service] → [Scoring API] → [Score Cache Redis]  [Feature Store Redis]
                              → [Champion/Challenger Router]
                              → [ONNX Inference (Champion + Challenger loaded)]
                              → [Reason Code Service]
                              → [Kafka: credit.score.computed] → [Audit Writer → PostgreSQL]
```

### Definition of Done

- Real bureau features refreshed from Kafka events → score uses actual CIBIL data
- Champion and challenger scores both stored in score_history (with model_role)
- Idempotency: same request_id returns same score (verified with load test)
- Consent enforcement: score request without valid consent → no bureau features
- P99 latency < 200ms at 20 RPS (half target load)

### Risks

- Bureau Integration Service not available in staging → feature pipeline cannot be tested end-to-end. Mitigation: mock Kafka producer for bureau events
- ONNX model file management in S3: ensure correct IAM permissions in staging environment

---

## V1: Full Production (Weeks 11–20, 5 Engineers)

**Goal:** Full product coverage, batch scoring, adverse action, model lifecycle management.

### Scope

- Additional products: HOME_LOAN, CREDIT_CARD, BNPL (separate model registrations per product type)
- Batch scoring: Spring Batch job — CronJob on EKS, 5M users nightly, 10 parallel partitions
- Account Aggregator features: Kafka consumer for `bank.statement.updated`
- Loan Performance features: Kafka Streams consumer for `emi.payment.received`
- Thin-file / NTC model: separate ONNX model for users with < 6 months credit history
- Model hot-reload: Kafka `model.promoted` event → S3 download → atomic OnnxSession swap
- Score change detection: `ScoreChangeDetector` → `credit.score.significant_change` events
- Adverse action notice: `GET /api/v1/scores/{id}/adverse-action-notice` endpoint
- Model registry API: full CRUD + promotion workflow with approval_token
- Feature inspection admin API: `GET /api/v1/features/{user_id}`
- Security hardening: mTLS (Istio), RBAC scopes, audit logging to `credit.audit.log` topic
- PostgreSQL partitioning: monthly partition management (pg_partman), first archival script
- HPA configuration: auto-scale based on CPU

### Architecture State

```
Full production architecture as described in doc 01 (high-level architecture)
All 4 product types
Both real-time and batch scoring
Champion-challenger across all products
```

### Definition of Done

- 5M batch scoring completes in < 45 minutes
- Adverse action notice generated correctly for POOR/VERY_POOR score bands
- Model hot-reload: new champion deployed without pod restart (verified in staging)
- Score change detection: loan service receives `credit.score.significant_change` within 60s
- P99 latency < 200ms at 50 RPS (full target load, load tested)
- Regulatory compliance test suite: all tests green

### Risks

- Batch scoring window: 5M users in 45 minutes requires Redis MGET throughput. Measure in staging with production-representative data size
- Score change detection: if previous score doesn't exist (new user) → no change event. Must handle gracefully
- Thin-file model: requires separate ONNX model training (data science dependency)

---

## V2: Reliability and Scale (Months 5–8, 7 Engineers)

**Goal:** Harden for 2× traffic growth, improve feature freshness, add model quality monitoring.

### Scope

- Real-time feature update latency: reduce from 60s to 15s (Kafka Streams optimization, smaller consumer groups)
- Redis cluster mode: migrate feature store to 3-node cluster (horizontal scaling, 32GB → 96GB capacity)
- Score cache fallback: serve from score_history PostgreSQL when Redis unavailable (already in failure scenario doc — implement now)
- PSI monitoring: weekly Spark job computes Population Stability Index, publishes to Grafana
- Feature drift alerting: Prometheus-based feature value distribution monitoring
- Canary deployment: Argo Rollouts integration for scoring engine application updates
- Score history archival: automated monthly archival to S3 Parquet (pg_partman + Lambda)
- Multi-AZ PostgreSQL: ensure RDS Multi-AZ enabled, test failover procedure
- PgBouncer: add connection pooling between scoring pods and PostgreSQL
- Model fairness monitoring: disparate impact ratio computed monthly (gender, geography)
- SHAP value storage: selective SHAP storage for adverse action and high-value decisions

### Architecture Evolution

```
Feature Store Redis (single) → Feature Store Redis Cluster (3-node)
Inline ONNX hot-reload → Validated hot-reload (SHA-256 check, warmup, atomic swap)
Manual archival → Automated monthly archival (pg_partman + Lambda)
```

### Definition of Done

- Redis cluster failover tested: feature store node failure → no scoring downtime
- Model fairness report: automated monthly generation
- P99 latency < 200ms at 100 RPS (2× target load)
- Score history archival: 3-month archive successfully queryable via Athena

---

## V3: ML Platform Integration (Months 9–15, 10 Engineers)

**Goal:** Decouple model training and serving. Migrate to production ML platform.

### Scope

- **Feast feature store:** migrate from raw Redis keys to Feast-managed feature store. Benefits: point-in-time feature retrieval for model training (eliminates training-serving skew), feature reuse across ML teams
- **Triton Inference Server:** extract ONNX model serving from scoring application to dedicated Triton pods. Scoring engine calls Triton via gRPC. GPU-backed Triton for < 1ms inference on ensemble models
- **MLflow integration:** model training tracked in MLflow. Model registry backed by MLflow registry (source of truth for model metadata, ONNX file path, training metrics)
- **Online feature computation:** migrate behavioral features from daily batch to Kafka Streams continuous computation (eliminate 24-hour staleness for behavioral features)
- **Multi-model scoring:** support multiple models per request (ensemble: XGBoost + logistic regression + behavioral rule engine → combined score)
- **Score API v2:** gRPC streaming for bulk real-time scoring (replace batch endpoint)
- **Continuous monitoring:** Evidently AI integration for automatic drift detection and alerting

### Architecture Evolution

```
In-process ONNX → Triton Inference Server (dedicated pods)
Raw Redis keys → Feast (Redis online store + BigQuery offline store)
Spring Batch nightly → Kafka Streams continuous (near-real-time features)
Manual fairness analysis → Automated Evidently AI drift + fairness monitoring
```

### Definition of Done

- Training-serving skew eliminated (Feast point-in-time retrieval verified)
- Triton inference: P99 < 2ms for single model (GPU-backed)
- Feature freshness: behavioral features updated within 30 seconds of transaction event
- Model promotion: full MLflow → Triton → production pipeline (no manual S3 upload)

---

## Team Scaling

| Phase | Engineers | Roles |
|---|---|---|
| Foundation | 2 | 1 backend (scoring engine), 1 ML (ONNX conversion + model validation) |
| MVP | 3 | +1 backend (feature pipeline + Kafka), ML remains |
| V1 | 5 | +1 platform (Kubernetes, CI/CD), +1 backend (batch scoring, admin APIs) |
| V2 | 7 | +1 data engineer (Spark archival, PSI), +1 SRE (reliability, Redis cluster) |
| V3 | 10 | +2 ML platform (Feast, Triton), +1 ML engineer (online features, ensemble) |

---

## What NOT to Build Early (Overengineering Risks)

| Temptation | Why to Defer |
|---|---|
| Feast feature store from day 1 | Operational complexity, no training pipeline yet, Redis is simpler |
| Triton Inference Server | Network overhead > in-process ONNX at 50 RPS |
| Online learning | Requires feature pipeline stability first |
| GraphQL API | No client asking for it; REST + gRPC covers all use cases |
| WASM model serving | Premature; ONNX Runtime Java is production-proven |
| Multi-region active-active | Not needed until regulatory expansion or > 2000 RPS |
