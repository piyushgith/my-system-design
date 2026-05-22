-- V3: Journal entries (immutable ledger lines)
-- MVP: no partitioning — added in V1 production roadmap via pg_partman
CREATE TABLE journal_entries (
    entry_id        UUID        NOT NULL DEFAULT gen_random_uuid(),
    posting_id      UUID        NOT NULL REFERENCES postings(posting_id),
    account_id      UUID        NOT NULL REFERENCES accounts(account_id),
    direction       VARCHAR(6)  NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount          BIGINT      NOT NULL CHECK (amount > 0),
    currency        CHAR(3)     NOT NULL,
    effective_at    TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    description     TEXT,
    sequence_num    BIGSERIAL,
    PRIMARY KEY (entry_id)
);

-- Hot path: account balance SUM and statement queries
CREATE INDEX idx_je_account_effective ON journal_entries(account_id, effective_at DESC, entry_id);
-- Posting lookup (fetch all legs of a posting)
CREATE INDEX idx_je_posting           ON journal_entries(posting_id);
-- Balance aggregation since watermark (used when snapshots added in V1)
CREATE INDEX idx_je_sequence          ON journal_entries(account_id, sequence_num);

-- App user must never UPDATE or DELETE journal entries (enforced by DB privileges in production)
-- In MVP: enforced by application-layer convention and code review
COMMENT ON TABLE journal_entries IS 'Immutable append-only ledger. No UPDATE or DELETE permitted.';
