-- Score audit log — immutable, never deleted
CREATE TABLE score_history (
    request_id        VARCHAR(36)   PRIMARY KEY,
    user_id           VARCHAR(36)   NOT NULL,
    score             INT           NOT NULL,
    raw_pd            DECIMAL(7,6)  NOT NULL,
    score_band        VARCHAR(20)   NOT NULL,
    model_version     VARCHAR(50)   NOT NULL,
    product_type      VARCHAR(30)   NOT NULL,
    feature_snapshot  TEXT          NOT NULL,
    reason_codes      TEXT          NOT NULL,
    source            VARCHAR(20)   NOT NULL,
    model_role        VARCHAR(20)   NOT NULL,
    computed_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    consent_ref_id    VARCHAR(36)
);

CREATE INDEX idx_sh_user_computed   ON score_history (user_id, computed_at DESC);
CREATE INDEX idx_sh_model_version   ON score_history (model_version, computed_at DESC);

-- Model lifecycle registry
CREATE TABLE model_registry (
    model_id                VARCHAR(36)   PRIMARY KEY,
    model_version           VARCHAR(50)   NOT NULL UNIQUE,
    model_type              VARCHAR(30)   NOT NULL,
    role                    VARCHAR(20)   NOT NULL,
    challenger_traffic_pct  INT           DEFAULT 0,
    product_types           TEXT          NOT NULL,
    s3_model_path           VARCHAR(500)  NOT NULL,
    feature_order           TEXT          NOT NULL,
    approved_by             VARCHAR(100),
    deployed_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retired_at              TIMESTAMP,
    notes                   TEXT
);

-- Feature metadata catalogue
CREATE TABLE feature_definitions (
    feature_id               VARCHAR(36)   PRIMARY KEY,
    feature_name             VARCHAR(100)  NOT NULL UNIQUE,
    feature_group            VARCHAR(30)   NOT NULL,
    description              TEXT,
    data_source              VARCHAR(30)   NOT NULL,
    refresh_frequency_hours  INT           NOT NULL,
    data_type                VARCHAR(20)   NOT NULL,
    default_value            DECIMAL,
    redis_key_pattern        VARCHAR(200)  NOT NULL,
    is_pii                   BOOLEAN       NOT NULL DEFAULT FALSE,
    regulatory_notes         TEXT
);
