CREATE SEQUENCE cif.cif_number_seq START WITH 10000001 INCREMENT BY 1;

DROP INDEX IF EXISTS ledger.idx_transactions_idempotency_key;
CREATE UNIQUE INDEX idx_transactions_idempotency_user ON ledger.transactions (idempotency_key, initiated_by);

CREATE TABLE accounts.open_idempotency (
    idempotency_key   VARCHAR(100) PRIMARY KEY,
    account_id        VARCHAR(25) NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_open_idempotency_account FOREIGN KEY (account_id) REFERENCES accounts.accounts(account_id)
);
