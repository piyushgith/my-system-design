-- Document metadata — actual files live in S3
CREATE TABLE application_documents (
    document_id     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id  UUID        NOT NULL REFERENCES loan_applications(application_id),
    document_type   VARCHAR(32) NOT NULL,     -- AADHAAR, PAN, SALARY_SLIP, BANK_STATEMENT, FORM_16
    s3_bucket       VARCHAR(128) NOT NULL,
    s3_key          VARCHAR(512) NOT NULL,
    file_size_bytes BIGINT,
    mime_type       VARCHAR(64),
    status          VARCHAR(24) NOT NULL DEFAULT 'UPLOADED',
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    verified_by     UUID,
    verified_at     TIMESTAMPTZ,

    CONSTRAINT chk_doc_status CHECK (status IN ('UPLOADED','VERIFIED','REJECTED'))
);

CREATE INDEX idx_documents_application ON application_documents (application_id);
