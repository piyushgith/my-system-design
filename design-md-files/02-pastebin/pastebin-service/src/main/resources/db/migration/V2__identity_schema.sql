CREATE SCHEMA IF NOT EXISTS identity;

CREATE TABLE identity.users (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255)    NOT NULL,
    email_verified  BOOLEAN         NOT NULL DEFAULT FALSE,
    password_hash   VARCHAR(128),
    display_name    VARCHAR(100),
    avatar_url      VARCHAR(512),
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_users_email ON identity.users (LOWER(email));
