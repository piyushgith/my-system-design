-- Every EMI collection or manual repayment — append-only
CREATE TABLE repayment_records (
    repayment_id        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_account_id     UUID         NOT NULL REFERENCES loan_accounts(loan_account_id),
    installment_number  INT,
    amount              NUMERIC(18,2) NOT NULL,
    principal_paid      NUMERIC(18,2) NOT NULL,
    interest_paid       NUMERIC(18,2) NOT NULL,
    penalty_paid        NUMERIC(18,2) NOT NULL DEFAULT 0,
    payment_method      VARCHAR(24)  NOT NULL,
    payment_reference   VARCHAR(128),
    source              VARCHAR(24)  NOT NULL,
    received_at         TIMESTAMPTZ  NOT NULL,
    applied_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    idempotency_key     VARCHAR(128) UNIQUE,    -- prevents double application
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_payment_method CHECK (payment_method IN ('NACH_DEBIT','NEFT','UPI','CASH')),
    CONSTRAINT chk_payment_source  CHECK (source IN ('AUTO_DEBIT','MANUAL','PREPAYMENT')),
    CONSTRAINT chk_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_payments_nonneg CHECK (principal_paid >= 0 AND interest_paid >= 0 AND penalty_paid >= 0)
);

CREATE INDEX idx_repayments_loan ON repayment_records (loan_account_id, received_at);
