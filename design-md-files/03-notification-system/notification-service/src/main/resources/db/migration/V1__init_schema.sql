-- =============================================================================
-- V1__init_schema.sql
-- Initial schema for notification-service (PostgreSQL 17)
-- =============================================================================

-- ---------------------------------------------------------------------------
-- notification_requests
-- ---------------------------------------------------------------------------
CREATE TABLE notification_requests (
    notification_id   UUID         NOT NULL,
    idempotency_key   VARCHAR(255) NOT NULL,
    category          VARCHAR(30)  NOT NULL,
    priority          VARCHAR(20)  NOT NULL,
    template_id       VARCHAR(100) NOT NULL,
    template_version  INTEGER,
    recipient_user_id UUID         NOT NULL,
    batch_id          UUID,
    variables         TEXT         NOT NULL,
    status            VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    scheduled_at      TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    dispatched_at     TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    producer_service  VARCHAR(100),
    producer_trace_id VARCHAR(255),

    CONSTRAINT pk_notification_requests    PRIMARY KEY (notification_id),
    CONSTRAINT uq_notification_idempotency UNIQUE      (idempotency_key)
);

-- Satisfies JPA index: recipient_user_id, status, created_at
CREATE INDEX idx_notification_user_status
    ON notification_requests (recipient_user_id, status, created_at);

-- ---------------------------------------------------------------------------
-- notification_channels_override  (ElementCollection for channelsOverride)
-- ---------------------------------------------------------------------------
CREATE TABLE notification_channels_override (
    notification_id UUID        NOT NULL,
    channel         VARCHAR(20) NOT NULL,

    CONSTRAINT fk_channels_override_notification
        FOREIGN KEY (notification_id)
        REFERENCES notification_requests (notification_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_channels_override_notification
    ON notification_channels_override (notification_id);

-- ---------------------------------------------------------------------------
-- delivery_attempts
-- ---------------------------------------------------------------------------
CREATE TABLE delivery_attempts (
    attempt_id          UUID        NOT NULL,
    notification_id     UUID        NOT NULL,
    channel             VARCHAR(20) NOT NULL,
    provider            VARCHAR(50) NOT NULL,
    provider_message_id VARCHAR(255),
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_number      INTEGER     NOT NULL DEFAULT 1,
    attempted_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delivered_at        TIMESTAMPTZ,
    failure_reason      VARCHAR(500),
    failure_code        VARCHAR(50),
    next_retry_at       TIMESTAMPTZ,

    CONSTRAINT pk_delivery_attempts PRIMARY KEY (attempt_id)
);

-- Satisfies JPA index: notification_id, channel, attempted_at
CREATE INDEX idx_delivery_notification
    ON delivery_attempts (notification_id, channel, attempted_at);

-- Satisfies JPA index: provider_message_id
CREATE INDEX idx_delivery_provider_ref
    ON delivery_attempts (provider_message_id)
    WHERE provider_message_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- outbox_events
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_events (
    event_id       UUID         NOT NULL,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMPTZ,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',

    CONSTRAINT pk_outbox_events PRIMARY KEY (event_id)
);

-- Satisfies JPA index: created_at
CREATE INDEX idx_outbox_pending
    ON outbox_events (created_at);

-- Partial index: polling query filters status = PENDING; avoids full scan on large tables
CREATE INDEX idx_outbox_unpublished
    ON outbox_events (created_at)
    WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- templates  (composite PK: template_id, version, channel, locale)
-- ---------------------------------------------------------------------------
CREATE TABLE templates (
    template_id      VARCHAR(100) NOT NULL,
    version          INTEGER      NOT NULL DEFAULT 1,
    channel          VARCHAR(20)  NOT NULL,
    locale           VARCHAR(10)  NOT NULL DEFAULT 'en-US',
    subject          VARCHAR(500),
    body_html        TEXT,
    body_text        TEXT         NOT NULL,
    push_title       VARCHAR(100),
    push_body        VARCHAR(255),
    variables_schema TEXT,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by       VARCHAR(100),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deprecated_at    TIMESTAMPTZ,

    CONSTRAINT pk_templates PRIMARY KEY (template_id, version, channel, locale)
);

-- Satisfies JPA index: template_id, channel, locale
CREATE INDEX idx_template_active
    ON templates (template_id, channel, locale);

-- Partial index: active template lookups are the hot path
CREATE INDEX idx_template_active_only
    ON templates (template_id, channel, locale)
    WHERE is_active = TRUE;

-- ---------------------------------------------------------------------------
-- user_notification_preferences  (composite PK: user_id, channel, category)
-- ---------------------------------------------------------------------------
CREATE TABLE user_notification_preferences (
    user_id              UUID        NOT NULL,
    channel              VARCHAR(20) NOT NULL,
    category             VARCHAR(30) NOT NULL,
    opted_in             BOOLEAN     NOT NULL DEFAULT TRUE,
    quiet_hours_start    TIME,
    quiet_hours_end      TIME,
    timezone             VARCHAR(50)          DEFAULT 'UTC',
    unsubscribe_token    VARCHAR(64),
    hard_unsubscribed    BOOLEAN     NOT NULL DEFAULT FALSE,
    hard_unsubscribed_at TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_user_notification_preferences PRIMARY KEY (user_id, channel, category),
    CONSTRAINT uq_unsubscribe_token             UNIQUE      (unsubscribe_token)
);

-- ---------------------------------------------------------------------------
-- in_app_inbox
-- ---------------------------------------------------------------------------
CREATE TABLE in_app_inbox (
    inbox_item_id   UUID         NOT NULL,
    notification_id UUID         NOT NULL,
    user_id         UUID         NOT NULL,
    title           VARCHAR(255) NOT NULL,
    body            TEXT         NOT NULL,
    action_url      VARCHAR(2048),
    image_url       VARCHAR(2048),
    is_read         BOOLEAN      NOT NULL DEFAULT FALSE,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_in_app_inbox PRIMARY KEY (inbox_item_id)
);

-- Satisfies both JPA indexes (same columns: user_id, created_at)
CREATE INDEX idx_inbox_user_unread
    ON in_app_inbox (user_id, created_at);

-- Partial index: unread items are the primary access pattern
CREATE INDEX idx_inbox_user_unread_filter
    ON in_app_inbox (user_id, created_at)
    WHERE is_read = FALSE;

-- TTL cleanup: expires_at used by scheduled purge jobs
CREATE INDEX idx_inbox_expires_at
    ON in_app_inbox (expires_at);
