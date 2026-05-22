CREATE TABLE users (
    user_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username        VARCHAR(50) UNIQUE NOT NULL,
    display_name    VARCHAR(100) NOT NULL,
    email           VARCHAR(255) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    profile_pic_url TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at    TIMESTAMPTZ,
    status_message  VARCHAR(255),
    account_status  VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
        CHECK (account_status IN ('ACTIVE', 'SUSPENDED', 'DELETED'))
);

CREATE TABLE conversations (
    conversation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type            VARCHAR(10) NOT NULL CHECK (type IN ('DIRECT', 'GROUP', 'CHANNEL')),
    name            VARCHAR(255),
    creator_id      UUID NOT NULL REFERENCES users(user_id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_message_id UUID,
    last_message_at TIMESTAMPTZ,
    member_count    INT NOT NULL DEFAULT 0,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    settings        JSONB DEFAULT '{}'
);

CREATE INDEX idx_conversations_last_message ON conversations(last_message_at DESC)
    WHERE is_deleted = FALSE;

CREATE TABLE conversation_members (
    conversation_id UUID NOT NULL REFERENCES conversations(conversation_id),
    user_id         UUID NOT NULL REFERENCES users(user_id),
    role            VARCHAR(10) NOT NULL DEFAULT 'MEMBER'
        CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_read_seq   BIGINT NOT NULL DEFAULT 0,
    is_muted        BOOLEAN NOT NULL DEFAULT FALSE,
    is_removed      BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX idx_members_user_id ON conversation_members(user_id)
    WHERE is_removed = FALSE;

CREATE TABLE messages (
    message_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id     UUID NOT NULL REFERENCES conversations(conversation_id),
    sender_id           UUID NOT NULL REFERENCES users(user_id),
    sequence_num        BIGINT NOT NULL,
    content_type        VARCHAR(20) NOT NULL DEFAULT 'TEXT',
    content             TEXT NOT NULL,
    sent_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    server_received_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    status              VARCHAR(20) NOT NULL DEFAULT 'SENT',
    is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    idempotency_key     VARCHAR(64),
    UNIQUE (conversation_id, sequence_num),
    UNIQUE (conversation_id, idempotency_key)
);

CREATE INDEX idx_messages_conversation_seq ON messages(conversation_id, sequence_num DESC);

CREATE TABLE user_devices (
    device_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(user_id),
    device_type     VARCHAR(10) NOT NULL DEFAULT 'WEB',
    platform        VARCHAR(10) NOT NULL DEFAULT 'WEB',
    push_token      TEXT,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    registered_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at    TIMESTAMPTZ
);

CREATE INDEX idx_devices_user_id ON user_devices(user_id) WHERE is_active = TRUE;
