CREATE TABLE ledger.transactions (
    txn_id              VARCHAR(30) PRIMARY KEY,
    txn_type            VARCHAR(30) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    amount_paise        BIGINT NOT NULL,
    currency            CHAR(3) NOT NULL DEFAULT 'INR',
    posting_date        DATE NOT NULL,
    value_date          DATE NOT NULL,
    narration           VARCHAR(500),
    reference_number    VARCHAR(50),
    idempotency_key     VARCHAR(100),
    initiated_by        VARCHAR(50) NOT NULL,
    initiated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    posted_at           TIMESTAMP WITH TIME ZONE,
    reversal_of         VARCHAR(30),
    response_snapshot   VARCHAR(8000)
);

CREATE TABLE ledger.journal_entries (
    entry_id        UUID NOT NULL DEFAULT RANDOM_UUID() PRIMARY KEY,
    txn_id          VARCHAR(30) NOT NULL,
    account_id      VARCHAR(25),
    gl_code         VARCHAR(20) NOT NULL,
    entry_type      CHAR(1) NOT NULL CHECK (entry_type IN ('D', 'C')),
    amount_paise    BIGINT NOT NULL CHECK (amount_paise > 0),
    currency        CHAR(3) NOT NULL DEFAULT 'INR',
    value_date      DATE NOT NULL,
    posting_date    DATE NOT NULL,
    posted_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    narration       VARCHAR(500),
    CONSTRAINT fk_journal_txn FOREIGN KEY (txn_id) REFERENCES ledger.transactions(txn_id)
);
