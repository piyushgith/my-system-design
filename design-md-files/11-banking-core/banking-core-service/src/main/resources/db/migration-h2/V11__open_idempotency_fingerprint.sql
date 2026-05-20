ALTER TABLE accounts.open_idempotency
    ADD COLUMN request_fingerprint VARCHAR(512);
