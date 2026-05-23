-- Month-by-month principal/interest breakdown — generated at loan activation
CREATE TABLE amortization_schedule (
    id                  BIGSERIAL    PRIMARY KEY,
    loan_account_id     UUID         NOT NULL REFERENCES loan_accounts(loan_account_id),
    installment_number  INT          NOT NULL,
    due_date            DATE         NOT NULL,
    opening_principal   NUMERIC(18,2) NOT NULL,
    emi_amount          NUMERIC(18,2) NOT NULL,
    principal_component NUMERIC(18,2) NOT NULL,
    interest_component  NUMERIC(18,2) NOT NULL,
    closing_principal   NUMERIC(18,2) NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'SCHEDULED',

    CONSTRAINT uq_schedule_installment UNIQUE (loan_account_id, installment_number),
    CONSTRAINT chk_schedule_status CHECK (status IN ('SCHEDULED','PAID','PARTIAL','MISSED','WAIVED')),
    -- Invariant: principal + interest must equal EMI (allows 1 paisa tolerance for rounding on last installment)
    CONSTRAINT chk_components_sum CHECK (
        ABS((principal_component + interest_component) - emi_amount) <= 0.01
    )
);

CREATE INDEX idx_schedule_loan ON amortization_schedule (loan_account_id, due_date);
CREATE INDEX idx_schedule_due  ON amortization_schedule (due_date, status)
    WHERE status IN ('SCHEDULED', 'PARTIAL');
