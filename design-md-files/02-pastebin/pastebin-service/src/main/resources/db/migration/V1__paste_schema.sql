CREATE SCHEMA IF NOT EXISTS paste;

CREATE SEQUENCE paste.short_key_seq START WITH 1000000 INCREMENT BY 1;

CREATE TABLE paste.pastes (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    short_key       VARCHAR(16)     NOT NULL,
    title           VARCHAR(255),
    language        VARCHAR(64)     NOT NULL DEFAULT 'plaintext',
    content_type    VARCHAR(16)     NOT NULL CHECK (content_type IN ('INLINE', 'S3')),
    content_inline  TEXT,
    content_s3_key  VARCHAR(512),
    content_size    BIGINT          NOT NULL,
    content_hash    CHAR(64)        NOT NULL,
    expires_at      TIMESTAMPTZ,
    is_deleted      BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMPTZ,
    deletion_reason VARCHAR(32),
    access_level    VARCHAR(16)     NOT NULL DEFAULT 'PUBLIC'
                    CHECK (access_level IN ('PUBLIC', 'UNLISTED', 'PRIVATE')),
    password_hash   VARCHAR(128),
    owner_id        UUID,
    is_abuse_flagged BOOLEAN        NOT NULL DEFAULT FALSE,
    view_count      BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_pastes_short_key
    ON paste.pastes (short_key)
    WHERE is_deleted = FALSE;

CREATE INDEX idx_pastes_expires_at
    ON paste.pastes (expires_at)
    WHERE expires_at IS NOT NULL AND is_deleted = FALSE;

CREATE INDEX idx_pastes_owner_created
    ON paste.pastes (owner_id, created_at DESC)
    WHERE is_deleted = FALSE AND owner_id IS NOT NULL;

CREATE TABLE paste.expiry_schedule (
    paste_id        UUID            PRIMARY KEY REFERENCES paste.pastes(id),
    expires_at      TIMESTAMPTZ     NOT NULL,
    processed       BOOLEAN         NOT NULL DEFAULT FALSE,
    processed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_expiry_schedule_pending
    ON paste.expiry_schedule (expires_at)
    WHERE processed = FALSE;
