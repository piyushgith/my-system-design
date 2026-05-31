CREATE SCHEMA IF NOT EXISTS urlschema;

CREATE TABLE short_urls (
    short_code   VARCHAR(10)  PRIMARY KEY,
    long_url     TEXT         NOT NULL,
    status       VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    expires_at   TIMESTAMP WITH TIME ZONE NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at   TIMESTAMP WITH TIME ZONE NULL,
    CONSTRAINT chk_short_urls_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'DELETED'))
);

CREATE INDEX idx_short_urls_expires_at ON short_urls (expires_at);
