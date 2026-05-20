CREATE SEQUENCE cif.cif_number_seq START WITH 10000001 INCREMENT BY 1;

DROP INDEX IF EXISTS ledger.idx_transactions_idempotency_key;
CREATE UNIQUE INDEX idx_transactions_idempotency_user
    ON ledger.transactions (idempotency_key, initiated_by)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE accounts.open_idempotency (
    idempotency_key   VARCHAR(100) PRIMARY KEY,
    account_id        VARCHAR(25) NOT NULL REFERENCES accounts.accounts(account_id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
