# 01 — High-Level Architecture: Credit Scoring Engine

---

## Objective

Define the architectural style, component model, and interaction patterns for the credit scoring engine. Justify the feature store + CQRS + event-driven architecture.

---

## Architecture Decision: CQRS + Feature Store + Event-Driven Feature Updates

### Chosen Architecture: CQRS with Precomputed Feature Store

**Why CQRS here?**

The credit scoring engine has two fundamentally different workloads:
1. **Command side:** Feature updates (transaction events, bureau data, account performance) — append-heavy, async
2. **Query side:** Score computation — read-heavy, latency-critical (< 200ms)

These two workloads have incompatible optimization requirements. The command side needs high write throughput and eventual consistency. The query side needs sub-10ms feature retrieval. CQRS separates them.

**The Feature Store is the read model.** Features are precomputed and stored in Redis. The real-time scoring path reads from Redis — no joins, no aggregations, no bureau API calls in the critical path.

**Why NOT compute features on-the-fly during scoring?**

If every score request triggered a live computation of all features (SQL aggregations over transaction history, bureau API call, etc.), the scoring latency would be 1–5 seconds — too slow for a loan application flow. The feature store trades feature freshness for speed.

**Why NOT use a dedicated ML platform (SageMaker, Vertex AI)?**

At the initial scale (50 RPS peak, 5M batch nightly), the operational overhead of SageMaker is not justified. An ONNX model served by the scoring engine itself is simpler. At 500 RPS+ or when model training + serving must be decoupled across teams, migrate to a managed ML platform.

---

## System Context

```mermaid
C4Context
    title Credit Scoring Engine — System Context

    System(loan, "Loan Origination Service", "Requests score for loan applicant")
    System(card, "Card Service", "Requests score for credit limit")
    System(scoring, "Credit Scoring Engine", "Computes credit score, returns score + reason codes")
    System_Ext(cibil, "CIBIL / Experian", "Bureau data provider")
    System_Ext(bureau_integrator, "Bureau Integration Service", "Fetches and caches bureau reports")
    System(feature_pipeline, "Feature Engineering Pipeline", "Computes features from raw events")
    System(ledger, "Ledger / Transaction Service", "Source of payment behavior data")
    System(account_aggregator, "Account Aggregator (AA)", "Bank statement data via Open Banking")
    System_Ext(kafka, "Kafka", "Feature update events, score change events")

    Rel(loan, scoring, "POST /scores {user_id, product_type}", "REST/gRPC")
    Rel(card, scoring, "POST /scores {user_id}", "REST/gRPC")
    Rel(scoring, feature_pipeline, "Reads precomputed features", "Redis")
    Rel(ledger, kafka, "Publishes transaction events")
    Rel(kafka, feature_pipeline, "Consumes transaction events")
    Rel(bureau_integrator, kafka, "Publishes bureau.data.refreshed events")
    Rel(kafka, feature_pipeline, "Consumes bureau events")
    Rel(feature_pipeline, scoring, "Writes features to Redis feature store")
    Rel(scoring, kafka, "Publishes credit.score.updated events")
    Rel(account_aggregator, kafka, "Publishes bank statement data")
```

---

## Component Architecture

```mermaid
graph TB
    subgraph Callers
        LOAN[Loan Service]
        CARD[Card Service]
        BATCH[Batch Scoring Job]
    end

    subgraph ScoringEngine["Credit Scoring Engine"]
        API[Scoring API<br/>REST + gRPC]
        CACHE[Score Cache<br/>recent scores]
        MODEL_SVC[Model Inference Service<br/>ONNX Runtime]
        FEATURE_SVC[Feature Assembly Service<br/>Reads from Redis feature store]
        REASON_SVC[Reason Code Service<br/>SHAP / coefficient mapping]
        SCORE_STORE[Score Storage<br/>Audit log writer]
        CHAMPION_CHALLENGER[A/B Router<br/>Champion/Challenger]
        EVENT_PUB[Score Event Publisher]
    end

    subgraph FeaturePipeline["Feature Engineering Pipeline"]
        TXN_CONSUMER[Transaction Feature Consumer]
        BUREAU_CONSUMER[Bureau Feature Consumer]
        AA_CONSUMER[Account Aggregator Consumer]
        FEATURE_WRITER[Feature Store Writer]
    end

    subgraph Storage
        REDIS_FEATURES[(Redis Feature Store<br/>15-20 features per user)]
        REDIS_SCORES[(Redis Score Cache<br/>recent scores TTL 5min)]
        PG[(PostgreSQL<br/>score_history<br/>model_registry<br/>feature_definitions)]
        S3[(S3<br/>model files<br/>bureau reports)]
    end

    subgraph Messaging
        KAFKA[Kafka<br/>transaction events<br/>bureau events<br/>score events]
    end

    LOAN --> API
    CARD --> API
    BATCH --> API

    API --> CACHE
    API --> CHAMPION_CHALLENGER
    CHAMPION_CHALLENGER --> MODEL_SVC
    MODEL_SVC --> FEATURE_SVC
    FEATURE_SVC --> REDIS_FEATURES
    MODEL_SVC --> REASON_SVC
    MODEL_SVC --> SCORE_STORE
    SCORE_STORE --> PG
    SCORE_STORE --> EVENT_PUB
    EVENT_PUB --> KAFKA

    TXN_CONSUMER --> KAFKA
    BUREAU_CONSUMER --> KAFKA
    AA_CONSUMER --> KAFKA
    TXN_CONSUMER --> FEATURE_WRITER
    BUREAU_CONSUMER --> FEATURE_WRITER
    AA_CONSUMER --> FEATURE_WRITER
    FEATURE_WRITER --> REDIS_FEATURES

    style ScoringEngine fill:#e3f2fd,stroke:#1565c0
    style FeaturePipeline fill:#e8f5e9,stroke:#2e7d32
```

---

## Scoring Request Flow

```mermaid
sequenceDiagram
    participant LOAN as Loan Service
    participant API as Scoring API
    participant CACHE as Score Cache (Redis)
    participant ROUTER as Champion/Challenger Router
    participant FEAT as Feature Assembly
    participant REDIS as Feature Store (Redis)
    participant MODEL as ONNX Model
    participant REASON as Reason Code Service
    participant DB as PostgreSQL (async)

    LOAN->>API: POST /scores {user_id, product_type, force_refresh=false}
    API->>CACHE: GET score:{user_id}:{product_type}
    alt Cache hit (< 5min old)
        CACHE-->>API: cached score
        API-->>LOAN: 200 OK {score, reason_codes} [< 2ms]
    else Cache miss
        CACHE-->>API: nil
        API->>ROUTER: route(user_id) → champion or challenger model
        ROUTER->>FEAT: assembleFeatures(user_id, product_type)
        FEAT->>REDIS: MGET [feature:user_id:bureau_score, feature:user_id:dpd_count, ...]
        REDIS-->>FEAT: [720, 0, 2, 0.35, ...] (15-20 feature values)
        FEAT-->>MODEL: FeatureVector [15-20 values]
        MODEL->>MODEL: ONNX inference (XGBoost predict_proba)
        MODEL-->>REASON: pd_score = 0.05, shap_values = [...]
        REASON-->>API: {score: 780, reason_codes: ["ON_TIME_PAYMENT", ...]}
        API->>CACHE: SET score:{user_id} TTL=5min (async)
        API->>DB: INSERT score_history (async, non-blocking)
        API-->>LOAN: 200 OK {score: 780, reason_codes, model_version, computed_at} [~15ms]
    end
```

---

## Feature Engineering Pipeline

```mermaid
graph LR
    subgraph Sources
        TXN[Transaction Events<br/>Kafka]
        BUREAU[Bureau Reports<br/>S3 via bureau.data.refreshed]
        AA[Account Aggregator<br/>Bank Statement Events]
        PERF[Loan Performance<br/>EMI payment events]
    end

    subgraph FeaturePipeline["Feature Pipeline (Kafka Streams / Spring Batch)"]
        REAL_TIME[Real-time Features<br/>Kafka Streams<br/>sliding window counts]
        BATCH_FEAT[Daily Batch Features<br/>Spring Batch<br/>30/60/90 day aggregates]
    end

    subgraph FeatureStore
        REDIS[(Redis Feature Store<br/>user feature namespaces)]
    end

    TXN -->|real-time| REAL_TIME
    TXN -->|daily aggregation| BATCH_FEAT
    BUREAU -->|on refresh event| BATCH_FEAT
    AA -->|daily| BATCH_FEAT
    PERF -->|on EMI event| REAL_TIME

    REAL_TIME --> REDIS
    BATCH_FEAT --> REDIS
```

---

## Champion-Challenger Architecture

The credit scoring engine runs two model versions simultaneously:

```
Incoming scoring request
  └── User ID hash % 100
        ├── 0–89 (90%) → Champion Model (production model, stable)
        └── 90–99 (10%) → Challenger Model (candidate model, being evaluated)

Score returned to caller: the model's output (caller doesn't know which model served)
Both scores stored in score_history with model_version column
```

**Purpose:** Scientifically compare model performance over time on real production data. After 30–60 days, the data science team compares:
- Default rates for champion-scored applications
- Default rates for challenger-scored applications

If the challenger performs better (lower default rate at same approval rate), promote it to champion.

**Promotion process:**
1. Data science team approves promotion after statistical significance check
2. Risk team reviews model validation report
3. Engineering deploys new model file to S3
4. Model registry updated: `champion_model_version` → new version
5. Scoring service loads new model on next hot-reload cycle (no redeploy)

---

## Architectural Tradeoffs

| Decision | Pro | Con |
|---|---|---|
| Precomputed feature store (Redis) | Sub-5ms feature retrieval; no DB joins on hot path | Features can be up to 24h stale for batch-updated features |
| ONNX model serving (in-process) | No network hop; < 3ms inference | Model update requires pod restart or hot-reload mechanism |
| Champion-challenger split | Scientific model comparison | Operational complexity; 10% of users get potentially worse scores |
| Score cache (5-minute TTL) | < 2ms for repeated requests | Loan application submitted minutes after a positive event may get slightly stale score |
| Async score storage | Non-blocking critical path | Score storage failure means audit gap (handled by Kafka outbox) |

---

## Interview Discussion Points

- **Why not call the bureau API on every score request?** Bureau API calls cost ₹15–₹50 per call and take 500ms–2 seconds. At 50 RPS: 50 × ₹50 = ₹2,500/minute = ₹3.6M/day. And the latency would exceed 200ms. The feature store pre-fetches bureau data and refreshes it every 30 days — aligns with bureau update frequency
- **Why precompute features instead of computing at score time?** At 50 RPS real-time scoring, computing `SELECT SUM(amount) FROM transactions WHERE user_id=? AND date > ? GROUP BY month` in real-time across 24 months of history is a scan of potentially millions of rows. With the feature store, this aggregate is precomputed and stored as a single Redis key — O(1) retrieval
- **What is the feature freshness problem and how do you handle it?** A user makes an EMI payment 10 minutes before applying for a new loan. The batch feature pipeline runs at midnight — the payment isn't reflected in the feature store yet. The real-time feature pipeline (Kafka Streams) processes payment events within 60 seconds and updates the relevant features (DPD count, payment velocity). This hybrid approach balances freshness and cost
