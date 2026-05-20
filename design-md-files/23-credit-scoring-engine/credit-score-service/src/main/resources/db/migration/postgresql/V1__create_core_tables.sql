-- Score audit log — immutable, append-only
CREATE TABLE score_history (
    request_id        UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID          NOT NULL,
    score             INT           NOT NULL,
    raw_pd            NUMERIC(7,6)  NOT NULL,
    score_band        VARCHAR(20)   NOT NULL,
    model_version     VARCHAR(50)   NOT NULL,
    product_type      VARCHAR(30)   NOT NULL,
    feature_snapshot  JSONB         NOT NULL,
    reason_codes      JSONB         NOT NULL DEFAULT '[]',
    source            VARCHAR(20)   NOT NULL,
    model_role        VARCHAR(20)   NOT NULL,
    computed_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    consent_ref_id    UUID
);

-- Fast pagination by user; hot path for history API
CREATE INDEX idx_sh_user_computed   ON score_history (user_id, computed_at DESC);
-- Model performance analytics — filter by version across time window
CREATE INDEX idx_sh_model_computed  ON score_history (model_version, computed_at DESC);
-- JSONB feature snapshot — supports containment queries (@>) for audit/compliance
CREATE INDEX idx_sh_feature_gin     ON score_history USING GIN (feature_snapshot);

-- Model lifecycle registry
CREATE TABLE model_registry (
    model_id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    model_version           VARCHAR(50)   NOT NULL UNIQUE,
    model_type              VARCHAR(30)   NOT NULL,
    role                    VARCHAR(20)   NOT NULL,
    challenger_traffic_pct  INT           NOT NULL DEFAULT 0,
    product_types           TEXT          NOT NULL,
    s3_model_path           VARCHAR(500)  NOT NULL,
    feature_order           TEXT          NOT NULL,
    approved_by             VARCHAR(100),
    deployed_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    retired_at              TIMESTAMPTZ,
    notes                   TEXT
);

-- Partial index: only live (non-retired) models — avoids full scan on registry lookup
CREATE UNIQUE INDEX idx_mr_active_role ON model_registry (role) WHERE retired_at IS NULL;

-- Feature metadata catalogue
CREATE TABLE feature_definitions (
    feature_id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    feature_name             VARCHAR(100)  NOT NULL UNIQUE,
    feature_group            VARCHAR(30)   NOT NULL,
    description              TEXT,
    data_source              VARCHAR(30)   NOT NULL,
    refresh_frequency_hours  INT           NOT NULL,
    data_type                VARCHAR(20)   NOT NULL,
    default_value            NUMERIC,
    redis_key_pattern        VARCHAR(200)  NOT NULL,
    is_pii                   BOOLEAN       NOT NULL DEFAULT FALSE,
    regulatory_notes         TEXT
);
