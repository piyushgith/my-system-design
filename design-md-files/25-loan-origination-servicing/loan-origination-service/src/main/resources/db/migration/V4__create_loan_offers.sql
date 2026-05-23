-- Approved loan offer presented to borrower
CREATE TABLE loan_offers (
    offer_id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id              UUID         NOT NULL REFERENCES loan_applications(application_id),
    status                      VARCHAR(24)  NOT NULL DEFAULT 'EXTENDED',
    approved_amount             NUMERIC(18,2) NOT NULL,
    interest_rate               NUMERIC(6,4) NOT NULL,   -- annual rate: 0.1250 = 12.5%
    tenure_months               INT          NOT NULL,
    emi_amount                  NUMERIC(18,2) NOT NULL,
    processing_fee              NUMERIC(18,2),
    valid_until                 TIMESTAMPTZ  NOT NULL,
    accepted_at                 TIMESTAMPTZ,
    disbursement_account_number VARCHAR(18),
    disbursement_ifsc           VARCHAR(11),
    nach_consent                BOOLEAN      NOT NULL DEFAULT FALSE,
    esign_reference             VARCHAR(128),
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_offer_status CHECK (status IN ('EXTENDED','ACCEPTED','EXPIRED')),
    CONSTRAINT chk_interest_positive CHECK (interest_rate > 0),
    CONSTRAINT chk_emi_positive CHECK (emi_amount > 0)
);

CREATE INDEX idx_offers_application ON loan_offers (application_id);
CREATE INDEX idx_offers_expiry ON loan_offers (valid_until) WHERE status = 'EXTENDED';
