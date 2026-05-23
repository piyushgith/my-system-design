-- Tracks each disbursement attempt step-by-step for idempotency and recovery
CREATE TABLE disbursement_sagas (
    saga_id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    offer_id            UUID         NOT NULL,
    status              VARCHAR(32)  NOT NULL DEFAULT 'INITIATED',
    ledger_reserved_at  TIMESTAMPTZ,
    bank_transfer_ref   VARCHAR(128),
    bank_transfer_at    TIMESTAMPTZ,
    bank_confirmed_at   TIMESTAMPTZ,
    loan_activated_at   TIMESTAMPTZ,
    compensated_at      TIMESTAMPTZ,
    failure_reason      TEXT,
    idempotency_key     VARCHAR(128) UNIQUE NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_saga_status CHECK (status IN (
        'INITIATED','LEDGER_RESERVED','BANK_CONFIRMED','COMPLETED','FAILED','COMPENSATED'
    ))
);

CREATE INDEX idx_saga_offer ON disbursement_sagas (offer_id);
