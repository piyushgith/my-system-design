CREATE UNIQUE INDEX idx_transactions_idempotency_key
    ON ledger.transactions (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_accounts_cif_id ON accounts.accounts (cif_id);
CREATE INDEX idx_accounts_status_type ON accounts.accounts (status, account_type);
CREATE INDEX idx_liens_account_active ON accounts.liens (account_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_journal_account_value_date ON ledger.journal_entries (account_id, value_date DESC);
CREATE INDEX idx_journal_txn_id ON ledger.journal_entries (txn_id);
CREATE INDEX idx_transactions_posting_status ON ledger.transactions (posting_date, status);
CREATE UNIQUE INDEX idx_customers_pan_hash ON cif.customers (pan_hash);
CREATE INDEX idx_kyc_cif_status ON cif.kyc_records (cif_id, status);
CREATE INDEX idx_audit_entity_occurred ON audit.audit_events (entity_id, occurred_at);
