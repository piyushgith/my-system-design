-- V2: Postings (atomic financial events)
CREATE TABLE postings (
    posting_id      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    reference_type  VARCHAR(50) NOT NULL,
    reference_id    UUID,
    status          VARCHAR(20) NOT NULL DEFAULT 'POSTED' CHECK (status IN ('PENDING','POSTED','REVERSED')),
    reversal_of     UUID        REFERENCES postings(posting_id),
    effective_at    TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    description     TEXT,
    metadata        JSONB
);

CREATE INDEX idx_postings_idempotency ON postings(idempotency_key);
CREATE INDEX idx_postings_reference   ON postings(reference_type, reference_id);
CREATE INDEX idx_postings_effective   ON postings(effective_at DESC);
CREATE INDEX idx_postings_status      ON postings(status);
