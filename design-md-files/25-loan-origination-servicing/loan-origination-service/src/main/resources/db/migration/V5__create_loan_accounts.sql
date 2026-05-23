-- Active loan account post-disbursement
CREATE TABLE loan_accounts (
    loan_account_id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_account_number     VARCHAR(32)  UNIQUE NOT NULL,
    application_id          UUID         NOT NULL REFERENCES loan_applications(application_id),
    borrower_id             UUID         NOT NULL REFERENCES borrowers(borrower_id),
    product_type            VARCHAR(32)  NOT NULL,
    status                  VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    original_principal      NUMERIC(18,2) NOT NULL,
    outstanding_principal   NUMERIC(18,2) NOT NULL,
    interest_rate           NUMERIC(6,4) NOT NULL,
    rate_type               VARCHAR(8)   NOT NULL DEFAULT 'FIXED',
    tenure_months           INT          NOT NULL,
    remaining_tenure_months INT          NOT NULL,
    disbursed_at            TIMESTAMPTZ  NOT NULL,
    first_due_date          DATE         NOT NULL,
    next_due_date           DATE,
    emi_amount              NUMERIC(18,2) NOT NULL,
    nach_mandate_id         VARCHAR(128),
    dpd                     INT          NOT NULL DEFAULT 0,    -- days past due
    npa_classified_at       TIMESTAMPTZ,
    closed_at               TIMESTAMPTZ,
    closure_reason          VARCHAR(64),   -- FULL_REPAYMENT, WRITE_OFF, FORECLOSURE
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version                 BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT chk_loan_status CHECK (status IN ('ACTIVE','CLOSED','NPA','WRITTEN_OFF','RESTRUCTURED')),
    CONSTRAINT chk_rate_type CHECK (rate_type IN ('FIXED','FLOATING')),
    CONSTRAINT chk_outstanding_nonnegative CHECK (outstanding_principal >= 0),
    CONSTRAINT chk_dpd_nonnegative CHECK (dpd >= 0)
);

CREATE INDEX idx_loans_borrower  ON loan_accounts (borrower_id, status);
CREATE INDEX idx_loans_status    ON loan_accounts (status);
CREATE INDEX idx_loans_next_due  ON loan_accounts (next_due_date) WHERE status = 'ACTIVE';
CREATE INDEX idx_loans_dpd       ON loan_accounts (dpd)           WHERE status = 'ACTIVE' AND dpd > 0;
CREATE INDEX idx_loans_app       ON loan_accounts (application_id);
