-- ============================================================
-- KYC Identity Verification Schema
-- PostgreSQL 17 | Flyway V1
-- ============================================================

-- Enable uuid generation (built-in since PG 13)
-- gen_random_uuid() requires pgcrypto or pg_uuidv4 in PG < 13;
-- in PG 13+ it is built in via core.

-- ============================================================
-- kyc_applications
-- Central record for each verification attempt.
-- personal_data_encrypted: AES-256-GCM encrypted JSONB blob.
-- pii_expires_at: regulatory retention deadline.
-- ============================================================
CREATE TABLE kyc_applications (
    application_id          UUID            NOT NULL DEFAULT gen_random_uuid(),
    user_id                 UUID            NOT NULL,
    kyc_tier                VARCHAR(20)     NOT NULL,
    status                  VARCHAR(60)     NOT NULL DEFAULT 'SUBMITTED',
    parent_application_id   UUID            REFERENCES kyc_applications(application_id),
    personal_data_encrypted BYTEA           NOT NULL,
    personal_data_key_version VARCHAR(50)   NOT NULL,
    idempotency_key         VARCHAR(255),
    rejection_reason        VARCHAR(100),
    assigned_reviewer       UUID,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    approved_at             TIMESTAMPTZ,
    rejected_at             TIMESTAMPTZ,
    pii_expires_at          TIMESTAMPTZ     NOT NULL,
    is_pii_purged           BOOLEAN         NOT NULL DEFAULT false,

    CONSTRAINT pk_kyc_applications PRIMARY KEY (application_id),
    CONSTRAINT chk_kyc_tier CHECK (kyc_tier IN ('BASIC', 'STANDARD', 'FULL')),
    CONSTRAINT chk_kyc_status CHECK (status IN (
        'SUBMITTED',
        'DOCUMENT_VERIFICATION_PENDING',
        'DOCUMENT_VERIFIED',
        'DOCUMENT_REJECTED',
        'LIVENESS_PENDING',
        'LIVENESS_PASSED',
        'LIVENESS_FAILED',
        'WATCHLIST_SCREENING',
        'WATCHLIST_CLEAR',
        'WATCHLIST_HIT',
        'MANUAL_REVIEW',
        'APPROVED',
        'REJECTED'
    )),
    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key)
);

-- One active (non-terminal) application per user at a time
CREATE UNIQUE INDEX idx_kyc_one_active_per_user
    ON kyc_applications (user_id)
    WHERE status NOT IN ('APPROVED', 'REJECTED');

-- Status polling by upstream service
CREATE INDEX idx_kyc_user_status
    ON kyc_applications (user_id, status, created_at DESC);

-- Retention purge scheduler (only unpurged rows)
CREATE INDEX idx_kyc_pii_expiry
    ON kyc_applications (pii_expires_at)
    WHERE is_pii_purged = false;

-- Reviewer assignment lookup
CREATE INDEX idx_kyc_reviewer
    ON kyc_applications (assigned_reviewer, status)
    WHERE status = 'MANUAL_REVIEW';

-- ============================================================
-- document_references
-- S3 object keys are encrypted — even storage pointers are PII.
-- ============================================================
CREATE TABLE document_references (
    doc_ref_id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    application_id          UUID            NOT NULL REFERENCES kyc_applications(application_id),
    s3_key_encrypted        BYTEA           NOT NULL,
    s3_key_version          VARCHAR(50)     NOT NULL,
    document_type           VARCHAR(30)     NOT NULL,
    side                    VARCHAR(10)     NOT NULL,
    uploaded_at             TIMESTAMPTZ     NOT NULL DEFAULT now(),
    is_purged               BOOLEAN         NOT NULL DEFAULT false,
    purged_at               TIMESTAMPTZ,

    CONSTRAINT pk_document_references PRIMARY KEY (doc_ref_id),
    CONSTRAINT chk_doc_side CHECK (side IN ('FRONT', 'BACK', 'BOTH')),
    CONSTRAINT chk_doc_type CHECK (document_type IN (
        'AADHAAR', 'PAN', 'PASSPORT', 'DRIVERS_LICENSE', 'SELFIE'
    ))
);

CREATE INDEX idx_docref_application
    ON document_references (application_id);

-- ============================================================
-- verification_steps
-- One row per step type per application. result is JSONB —
-- vendor-specific shape varies across OCR/liveness/watchlist.
-- ============================================================
CREATE TABLE verification_steps (
    step_id                 UUID            NOT NULL DEFAULT gen_random_uuid(),
    application_id          UUID            NOT NULL REFERENCES kyc_applications(application_id),
    step_type               VARCHAR(30)     NOT NULL,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    vendor                  VARCHAR(30),
    vendor_reference_id     VARCHAR(255),
    result                  JSONB,
    started_at              TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    retry_count             INT             NOT NULL DEFAULT 0,
    failure_reason          TEXT,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_verification_steps PRIMARY KEY (step_id),
    CONSTRAINT chk_step_type CHECK (step_type IN (
        'DOCUMENT_OCR', 'LIVENESS', 'WATCHLIST_SCREENING', 'MANUAL_REVIEW'
    )),
    CONSTRAINT chk_step_status CHECK (status IN (
        'PENDING', 'IN_PROGRESS', 'PASS', 'FAIL', 'SKIPPED'
    )),
    -- One step of each type per application
    CONSTRAINT uq_step_type_per_app UNIQUE (application_id, step_type)
);

-- Pending step query for orchestrator
CREATE INDEX idx_steps_pending
    ON verification_steps (application_id, status)
    WHERE status IN ('PENDING', 'IN_PROGRESS');

-- Webhook-to-step matching
CREATE INDEX idx_step_vendor_ref
    ON verification_steps (vendor, vendor_reference_id)
    WHERE vendor_reference_id IS NOT NULL;

-- ============================================================
-- state_transitions
-- Append-only audit log. APPROVED/REJECTED are terminal states.
-- No UPDATE or DELETE granted to the application role.
-- ============================================================
CREATE TABLE state_transitions (
    transition_id           UUID            NOT NULL DEFAULT gen_random_uuid(),
    application_id          UUID            NOT NULL REFERENCES kyc_applications(application_id),
    from_status             VARCHAR(60),
    to_status               VARCHAR(60)     NOT NULL,
    trigger_source          VARCHAR(20)     NOT NULL,
    triggered_by            VARCHAR(255)    NOT NULL,
    reason                  TEXT            NOT NULL,
    occurred_at             TIMESTAMPTZ     NOT NULL DEFAULT now(),
    metadata                JSONB,

    CONSTRAINT pk_state_transitions PRIMARY KEY (transition_id),
    CONSTRAINT chk_trigger_source CHECK (trigger_source IN (
        'SYSTEM', 'OPERATOR', 'API_CALLBACK', 'SCHEDULER'
    ))
);

-- Audit trail retrieval in chronological order
CREATE INDEX idx_transitions_app
    ON state_transitions (application_id, occurred_at ASC);

-- Compliance range query (e.g., all MANUAL_REVIEW transitions last 30 days)
CREATE INDEX idx_transitions_to_status
    ON state_transitions (to_status, occurred_at DESC);

-- ============================================================
-- manual_review_queue
-- One entry per application (UNIQUE on application_id).
-- Priority: HIGH > MEDIUM > LOW (ordered by priority DESC, created_at ASC).
-- ============================================================
CREATE TABLE manual_review_queue (
    review_id               UUID            NOT NULL DEFAULT gen_random_uuid(),
    application_id          UUID            NOT NULL REFERENCES kyc_applications(application_id),
    priority                VARCHAR(10)     NOT NULL DEFAULT 'MEDIUM',
    routing_reason          VARCHAR(50)     NOT NULL,
    assigned_reviewer       UUID,
    assigned_at             TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    decision                VARCHAR(20),
    notes                   TEXT,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_manual_review_queue PRIMARY KEY (review_id),
    CONSTRAINT uq_review_application UNIQUE (application_id),
    CONSTRAINT chk_review_priority CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT chk_review_routing CHECK (routing_reason IN (
        'WATCHLIST_HIT', 'DOCUMENT_REJECTED', 'LIVENESS_FAILED', 'VENDOR_ERROR', 'MANUAL_ESCALATION'
    )),
    CONSTRAINT chk_review_decision CHECK (decision IN ('APPROVED', 'REJECTED') OR decision IS NULL)
);

-- Priority queue: unassigned items, high-priority and oldest first
CREATE INDEX idx_review_unassigned
    ON manual_review_queue (priority DESC, created_at ASC)
    WHERE assigned_reviewer IS NULL AND completed_at IS NULL;

-- ============================================================
-- webhook_events
-- Raw vendor callback log. Stored before processing for audit
-- and replay. Idempotent via (vendor, vendor_reference_id).
-- ============================================================
CREATE TABLE webhook_events (
    webhook_id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    vendor                  VARCHAR(30)     NOT NULL,
    vendor_reference_id     VARCHAR(255)    NOT NULL,
    raw_payload             JSONB           NOT NULL,
    signature_valid         BOOLEAN         NOT NULL,
    processed               BOOLEAN         NOT NULL DEFAULT false,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    processed_at            TIMESTAMPTZ,

    CONSTRAINT pk_webhook_events PRIMARY KEY (webhook_id),
    CONSTRAINT uq_webhook_vendor_ref UNIQUE (vendor, vendor_reference_id)
);

CREATE INDEX idx_webhook_unprocessed
    ON webhook_events (created_at)
    WHERE processed = false;
