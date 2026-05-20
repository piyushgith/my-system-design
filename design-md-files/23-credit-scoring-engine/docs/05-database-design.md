# 05 — Database Design: Credit Scoring Engine

---

## Objective

Design the database schema for score history, model registry, and feature definitions. The feature store is Redis (covered in Caching Strategy). PostgreSQL stores the audit-critical, long-lived data.

---

## Technology Choices

| Data | Store | Reason |
|---|---|---|
| Feature store (current values) | Redis | Sub-5ms retrieval; O(1) lookup per feature key |
| Score history (audit log) | PostgreSQL | ACID, long-term retention, time-range queries |
| Model registry | PostgreSQL | Relational, consistent model lifecycle tracking |
| Feature definitions | PostgreSQL | Master data — source of truth for feature metadata |
| Model files (ONNX) | S3 | Binary blobs, versioned, not queryable |
| Bureau raw reports | S3 | Encrypted, large, accessed rarely |

---

## PostgreSQL Schema

### Table: `score_history`

Every score computation stored here — the immutable audit log.

```sql
CREATE TABLE score_history (
    request_id      UUID        PRIMARY KEY,
    user_id         UUID        NOT NULL,
    score           INT         NOT NULL CHECK (score BETWEEN 300 AND 900),
    raw_pd          DECIMAL(6,5) NOT NULL CHECK (raw_pd BETWEEN 0 AND 1),
    score_band      VARCHAR(20) NOT NULL,
    model_version   VARCHAR(50) NOT NULL,
    product_type    VARCHAR(30) NOT NULL,
    feature_snapshot JSONB      NOT NULL,  -- all feature values at computation time
    reason_codes    JSONB       NOT NULL,  -- list of ReasonCode objects
    shap_values     JSONB,                 -- optional, can be large
    source          VARCHAR(20) NOT NULL CHECK (source IN ('REAL_TIME','BATCH','CACHE')),
    model_role      VARCHAR(20) NOT NULL CHECK (model_role IN ('CHAMPION','CHALLENGER','SHADOW')),
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    consent_ref_id  UUID
) PARTITION BY RANGE (computed_at);

-- Monthly partitions
CREATE TABLE score_history_2024_01 PARTITION OF score_history
    FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');

-- Indexes (on each partition)
CREATE INDEX idx_sh_user_computed ON score_history(user_id, computed_at DESC);
CREATE INDEX idx_sh_model_version ON score_history(model_version, computed_at DESC);
```

**`feature_snapshot` JSONB:** stores exactly which feature values were used. Enables future "what would the score be with different features?" analysis (counterfactual).

**`shap_values` nullable:** SHAP computation is optional (adds ~1ms). Stored when computed, skipped for batch runs (too much data: 5M rows × 50 features × 8 bytes = 2 GB/night).

**Partitioned by `computed_at`:** Score history grows at 5M rows/month. Monthly partitions allow:
- Fast range queries: `WHERE computed_at BETWEEN '2024-01-01' AND '2024-02-01'` → single partition scan
- Archival: old partitions (> 7 years) archived to S3 Parquet

---

### Table: `model_registry`

```sql
CREATE TABLE model_registry (
    model_id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    model_version       VARCHAR(50) NOT NULL UNIQUE,
    model_type          VARCHAR(30) NOT NULL,  -- XGBOOST, LIGHTGBM, LOGISTIC_REGRESSION
    role                VARCHAR(20) NOT NULL CHECK (role IN ('CHAMPION','CHALLENGER','SHADOW','RETIRED')),
    challenger_traffic_pct INT      DEFAULT 0 CHECK (challenger_traffic_pct BETWEEN 0 AND 100),
    product_types       VARCHAR[]   NOT NULL,
    s3_model_path       VARCHAR(500) NOT NULL,
    feature_definitions JSONB       NOT NULL,  -- ordered list of feature names the model expects
    score_thresholds    JSONB       NOT NULL,  -- {min_pd: 0.0, max_pd: 1.0, score_min: 300, score_max: 900}
    validation_report_s3 VARCHAR(500),
    approved_by         VARCHAR(100),
    deployed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    retired_at          TIMESTAMPTZ,
    notes               TEXT
);

-- Exactly one CHAMPION per product type at any time
CREATE UNIQUE INDEX idx_model_champion_product
    ON model_registry(product_types, role)
    WHERE role = 'CHAMPION';
```

**`feature_definitions` JSONB example:**
```json
[
  {"name": "bureau.cibil_score", "position": 0, "default": 0},
  {"name": "bureau.dpd_last_6m", "position": 1, "default": 0},
  {"name": "bureau.credit_utilization", "position": 2, "default": 0.5}
]
```

The `position` determines the order in the feature vector passed to ONNX — order matters for most ML frameworks.

---

### Table: `feature_definitions`

Master data: all features the system knows about, their sources, and refresh frequency.

```sql
CREATE TABLE feature_definitions (
    feature_id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    feature_name            VARCHAR(100) NOT NULL UNIQUE,
    feature_group           VARCHAR(30) NOT NULL,  -- BUREAU, BEHAVIORAL, PERFORMANCE, ACCOUNT
    description             TEXT,
    data_source             VARCHAR(30) NOT NULL,  -- CIBIL, LEDGER, AA, INTERNAL
    refresh_frequency_hours INT         NOT NULL,  -- 720 for bureau (30 days), 1 for real-time events
    data_type               VARCHAR(20) NOT NULL,  -- INT, FLOAT, BOOLEAN, CATEGORICAL
    default_value           DECIMAL,               -- used for thin-file / missing feature
    redis_key_pattern       VARCHAR(200) NOT NULL, -- e.g., "feature:{user_id}:bureau.cibil_score"
    is_pii                  BOOLEAN     NOT NULL DEFAULT false,
    regulatory_notes        TEXT
);
```

**Is this feature PII?** `bureau.cibil_score` is not PII (a number). `bureau.full_name` would be PII — but names are never stored in the feature store (only aggregated numerical signals).

---

### Table: `feature_refresh_log`

Tracks when features were last updated for each user. Used to detect stale features.

```sql
CREATE TABLE feature_refresh_log (
    log_id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID        NOT NULL,
    feature_group   VARCHAR(30) NOT NULL,
    refreshed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    triggered_by    VARCHAR(50) NOT NULL,  -- BATCH_JOB, KAFKA_EVENT, MANUAL
    records_updated INT
);

CREATE INDEX idx_frl_user_group ON feature_refresh_log(user_id, feature_group, refreshed_at DESC);
```

---

### Table: `batch_scoring_jobs`

Tracks batch scoring run metadata.

```sql
CREATE TABLE batch_scoring_jobs (
    job_id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    job_name        VARCHAR(100) NOT NULL,
    model_version   VARCHAR(50) NOT NULL,
    product_type    VARCHAR(30) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    total_users     INT,
    scored_users    INT         DEFAULT 0,
    failed_users    INT         DEFAULT 0,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    error_message   TEXT
);
```

---

## Feature Store: Redis Schema

The feature store is Redis — not PostgreSQL. Documented here for completeness.

### Key Pattern: `feature:{user_id}:{feature_name}`

**Examples:**
```
feature:usr_abc123:bureau.cibil_score         → "720"
feature:usr_abc123:bureau.dpd_last_6m         → "0"
feature:usr_abc123:bureau.credit_utilization   → "0.35"
feature:usr_abc123:behavior.upi_txn_count_30d  → "45"
feature:usr_abc123:behavior.avg_monthly_credit  → "85000.00"
feature:usr_abc123:meta.bureau_as_of            → "2024-01-01T00:00:00Z"
feature:usr_abc123:meta.is_thin_file            → "false"
```

**Retrieval: Redis `MGET`**
```
MGET feature:usr_abc123:bureau.cibil_score
     feature:usr_abc123:bureau.dpd_last_6m
     feature:usr_abc123:bureau.credit_utilization
     ...
```

One `MGET` for all 15–20 features → single round-trip → < 5ms.

**TTL strategy:**
- Bureau features: TTL = 32 days (30 days data freshness + 2 days buffer)
- Behavioral features: TTL = 2 days (batch refresh daily; 2-day TTL allows overnight miss without eviction)
- Real-time features: TTL = 24 hours (recomputed from events daily)
- No TTL on `meta.*` keys — always present after first profile creation

---

## Indexing Strategy

| Table | Index | Purpose |
|---|---|---|
| score_history | `(user_id, computed_at DESC)` | Latest score for a user |
| score_history | `(model_version, computed_at DESC)` | Champion vs challenger performance comparison |
| model_registry | `(product_types, role) WHERE role='CHAMPION'` | Fast champion model lookup |
| feature_refresh_log | `(user_id, feature_group, refreshed_at DESC)` | Stale feature detection |

---

## Data Retention

| Data | Retention | Action |
|---|---|---|
| `score_history` | 7 years | Archive partitions older than 7 years to S3 Parquet |
| `model_registry` | Indefinite | Models are business records — never delete |
| `feature_refresh_log` | 1 year | Delete older records (diagnostic, not regulatory) |
| `batch_scoring_jobs` | 1 year | Delete older records |
| Bureau raw reports (S3) | 5 years | S3 lifecycle: Standard → Glacier after 2 years |
| ONNX model files (S3) | Indefinite | Each model version kept forever (reproducibility) |

---

## Interview Discussion Points

- **Why store `feature_snapshot` JSONB in score_history rather than a foreign key to the feature store?** The feature store (Redis) is mutable — features get updated. If we stored only a reference to Redis, we couldn't reproduce the score 6 months later for audit. The snapshot captures the exact values used, making each score independently reproducible
- **How do you query "what was the average score of users approved for personal loans in Q1 2024?"** `SELECT AVG(score) FROM score_history WHERE product_type='PERSONAL_LOAN' AND computed_at BETWEEN '2024-01-01' AND '2024-04-01' AND source='REAL_TIME'` — partition pruning on `computed_at` makes this fast (only the Q1 partition is scanned)
- **How do you compare champion vs challenger model performance?** `SELECT model_role, model_version, COUNT(*) as scored, AVG(raw_pd) as avg_pd FROM score_history WHERE computed_at > '2024-01-10' GROUP BY model_role, model_version` — then join against actual default outcomes (from loan performance data) after 6–12 months. Models are compared on Gini coefficient and KS statistic
- **Why is the UNIQUE INDEX on model_registry for CHAMPION per product type important?** Two models cannot both be CHAMPION for the same product — that would make routing ambiguous. The DB constraint enforces this at the persistence layer, preventing a race condition during model promotion where both old and new champion are briefly active
