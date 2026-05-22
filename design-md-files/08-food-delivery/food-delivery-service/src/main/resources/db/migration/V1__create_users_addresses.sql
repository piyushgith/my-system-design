CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(100) NOT NULL,
    email            VARCHAR(255) NOT NULL UNIQUE,
    phone            VARCHAR(20)  NOT NULL UNIQUE,
    password_hash    VARCHAR(255),
    status           VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    loyalty_points   INTEGER      NOT NULL DEFAULT 0,
    default_address_id UUID,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE addresses (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL REFERENCES users(id),
    label        VARCHAR(20)  NOT NULL DEFAULT 'HOME',
    full_address TEXT         NOT NULL,
    city         VARCHAR(100) NOT NULL,
    pin_code     VARCHAR(10)  NOT NULL,
    country      VARCHAR(50)  NOT NULL DEFAULT 'IN',
    latitude     NUMERIC(9,6) NOT NULL,
    longitude    NUMERIC(9,6) NOT NULL,
    landmark     VARCHAR(200),
    is_default   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

ALTER TABLE users
    ADD CONSTRAINT fk_users_default_address
    FOREIGN KEY (default_address_id) REFERENCES addresses(id);

CREATE INDEX idx_addresses_user_id ON addresses(user_id);
CREATE INDEX idx_users_phone       ON users(phone);
CREATE INDEX idx_users_email       ON users(email);
