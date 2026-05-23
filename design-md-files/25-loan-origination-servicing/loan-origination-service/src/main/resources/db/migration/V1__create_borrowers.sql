-- Borrower profile — identity and KYC status
CREATE TABLE borrowers (
    borrower_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id         VARCHAR(64)  UNIQUE NOT NULL,
    full_name           VARCHAR(256) NOT NULL,
    date_of_birth       DATE         NOT NULL,
    pan_number          VARCHAR(10)  UNIQUE,
    aadhaar_hash        VARCHAR(64),          -- SHA-256 of masked Aadhaar, never raw
    mobile_number       VARCHAR(15),
    email               VARCHAR(256),
    kyc_status          VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    kyc_completed_at    TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    version             BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_borrowers_pan ON borrowers (pan_number) WHERE pan_number IS NOT NULL;
CREATE INDEX idx_borrowers_external ON borrowers (external_id);
