-- ============================================================
-- CRM MVP — Phase 0 Schema (single-tenant)
-- No RLS, no tenant_id. Multi-tenancy added in Phase 1.
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ------------------------------------------------------------
-- Users
-- ------------------------------------------------------------
CREATE TABLE users (
    user_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    full_name     VARCHAR(255),
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(50)  NOT NULL DEFAULT 'SALES_REP',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users (email);

-- ------------------------------------------------------------
-- Contacts
-- ------------------------------------------------------------
CREATE TABLE contacts (
    contact_id  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name  VARCHAR(255),
    last_name   VARCHAR(255),
    email       VARCHAR(255),
    phone       VARCHAR(50),
    company     VARCHAR(255),
    notes       TEXT,
    lead_status VARCHAR(50),  -- NULL = not a lead; NEW | CONTACTED | QUALIFIED | CONVERTED | LOST
    owner_id    UUID         REFERENCES users(user_id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ
);

CREATE INDEX idx_contacts_owner    ON contacts (owner_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_contacts_email    ON contacts (email)    WHERE deleted_at IS NULL;
CREATE INDEX idx_contacts_status   ON contacts (lead_status) WHERE deleted_at IS NULL;
CREATE INDEX idx_contacts_created  ON contacts (created_at DESC);

-- ------------------------------------------------------------
-- Pipelines
-- ------------------------------------------------------------
CREATE TABLE pipelines (
    pipeline_id UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ------------------------------------------------------------
-- Stages (ordered within a pipeline)
-- ------------------------------------------------------------
CREATE TABLE stages (
    stage_id    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_id UUID        NOT NULL REFERENCES pipelines(pipeline_id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    stage_order INT          NOT NULL,
    probability INT          NOT NULL DEFAULT 0 CHECK (probability BETWEEN 0 AND 100),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (pipeline_id, stage_order)
);

CREATE INDEX idx_stages_pipeline ON stages (pipeline_id, stage_order);

-- ------------------------------------------------------------
-- Deals
-- ------------------------------------------------------------
CREATE TABLE deals (
    deal_id             UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    title               VARCHAR(500)   NOT NULL,
    value               DECIMAL(15, 2),
    currency            CHAR(3)        NOT NULL DEFAULT 'USD',
    pipeline_id         UUID           NOT NULL REFERENCES pipelines(pipeline_id),
    stage_id            UUID           NOT NULL REFERENCES stages(stage_id),
    contact_id          UUID           REFERENCES contacts(contact_id),
    owner_id            UUID           NOT NULL REFERENCES users(user_id),
    expected_close_date DATE,
    status              VARCHAR(20)    NOT NULL DEFAULT 'OPEN', -- OPEN | WON | LOST
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    closed_at           TIMESTAMPTZ,
    deleted_at          TIMESTAMPTZ
);

CREATE INDEX idx_deals_pipeline_stage ON deals (pipeline_id, stage_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_deals_owner_status   ON deals (owner_id, status)       WHERE deleted_at IS NULL;
CREATE INDEX idx_deals_contact        ON deals (contact_id)              WHERE deleted_at IS NULL;
CREATE INDEX idx_deals_created        ON deals (created_at DESC);

-- ------------------------------------------------------------
-- Seed: default pipeline + stages
-- ------------------------------------------------------------
WITH inserted_pipeline AS (
    INSERT INTO pipelines (name, is_default)
    VALUES ('Sales Pipeline', TRUE)
    RETURNING pipeline_id
)
INSERT INTO stages (pipeline_id, name, stage_order, probability)
SELECT p.pipeline_id, s.name, s.ord, s.prob
FROM inserted_pipeline p,
     (VALUES
         ('Prospecting',  1, 10),
         ('Qualification', 2, 25),
         ('Proposal',     3, 50),
         ('Negotiation',  4, 75),
         ('Closed Won',   5, 100),
         ('Closed Lost',  6, 0)
     ) AS s(name, ord, prob);
