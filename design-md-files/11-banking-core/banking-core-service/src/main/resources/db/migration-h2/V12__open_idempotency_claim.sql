CREATE TABLE accounts.open_idempotency_claim (
    idempotency_key   VARCHAR(100) PRIMARY KEY,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
