-- Loan application lifecycle: DRAFT → SUBMITTED → UNDER_REVIEW → APPROVED/REJECTED → OFFER_EXTENDED → OFFER_ACCEPTED → DISBURSED
CREATE TABLE loan_applications (
    application_id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    borrower_id             UUID         NOT NULL REFERENCES borrowers(borrower_id),
    product_type            VARCHAR(32)  NOT NULL,
    status                  VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    requested_amount        NUMERIC(18,2) NOT NULL,
    requested_tenure_months INT          NOT NULL,
    purpose                 VARCHAR(64),
    monthly_income          NUMERIC(18,2),
    submitted_at            TIMESTAMPTZ,
    decided_at              TIMESTAMPTZ,
    expiry_at               TIMESTAMPTZ,         -- offer validity deadline
    rejection_reason        VARCHAR(256),
    underwriting_payload    JSONB,               -- bureau score, DTI, rule evaluation results
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version                 BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT chk_product_type CHECK (product_type IN ('PERSONAL_LOAN','HOME_LOAN','BNPL')),
    CONSTRAINT chk_application_status CHECK (status IN (
        'DRAFT','SUBMITTED','UNDER_REVIEW','APPROVED','REJECTED',
        'OFFER_EXTENDED','OFFER_ACCEPTED','OFFER_EXPIRED','DISBURSED'
    )),
    CONSTRAINT chk_tenure_positive CHECK (requested_tenure_months > 0),
    CONSTRAINT chk_amount_positive CHECK (requested_amount > 0)
);

CREATE INDEX idx_applications_borrower    ON loan_applications (borrower_id);
CREATE INDEX idx_applications_status      ON loan_applications (status, submitted_at);
CREATE INDEX idx_applications_decided     ON loan_applications (decided_at) WHERE decided_at IS NOT NULL;
