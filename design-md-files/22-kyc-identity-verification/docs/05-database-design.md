# 05 — Database Design: KYC / Identity Verification Pipeline

---

## Objective

Design the database schema, indexing, PII encryption at the storage layer, partitioning, and data retention enforcement for the KYC service.

---

## Technology Choice: PostgreSQL

**Why PostgreSQL?**

- JSONB for flexible OCR result and step-specific data (each vendor returns different fields)
- Native partial unique indexes (enforce one active application per user)
- Row-level security for PII access control
- ACID transactions for state machine transitions
- Supports encryption at column level via pgcrypto (though application-layer AES-256-GCM is preferred)

**Why not MongoDB?** KYC data has relational constraints (application → steps → transitions) and requires ACID guarantees for state transitions. MongoDB's eventual consistency model is not appropriate for a compliance system.

---

## Schema Design

### Table: `kyc_applications`

```sql
CREATE TABLE kyc_applications (
    application_id      UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID            NOT NULL,
    kyc_tier            VARCHAR(20)     NOT NULL CHECK (kyc_tier IN ('BASIC','STANDARD','FULL')),
    status              VARCHAR(50)     NOT NULL DEFAULT 'SUBMITTED',
    parent_application_id UUID          REFERENCES kyc_applications(application_id),
    personal_data_encrypted BYTEA       NOT NULL,  -- AES-256-GCM encrypted JSONB
    personal_data_key_version VARCHAR(50) NOT NULL, -- KMS key version for decryption
    idempotency_key     VARCHAR(255)    UNIQUE,
    rejection_reason    VARCHAR(100),
    assigned_reviewer   UUID,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    approved_at         TIMESTAMPTZ,
    rejected_at         TIMESTAMPTZ,
    pii_expires_at      TIMESTAMPTZ     NOT NULL,  -- Regulatory retention deadline
    is_pii_purged       BOOLEAN         NOT NULL DEFAULT false
);

-- One active application per user (not in terminal state)
CREATE UNIQUE INDEX idx_kyc_one_active_per_user
    ON kyc_applications(user_id)
    WHERE status NOT IN ('APPROVED', 'REJECTED');

-- Fast status check by user
CREATE INDEX idx_kyc_user_status ON kyc_applications(user_id, status, created_at DESC);

-- PII purge scheduler
CREATE INDEX idx_kyc_pii_expiry ON kyc_applications(pii_expires_at)
    WHERE is_pii_purged = false;

-- Reviewer assignment query
CREATE INDEX idx_kyc_reviewer ON kyc_applications(assigned_reviewer, status)
    WHERE status = 'MANUAL_REVIEW';
```

**`personal_data_encrypted`:** The entire personal data blob (name, DOB, address, etc.) is AES-256-GCM encrypted at the application layer before storing. PostgreSQL stores opaque bytes. Only the application process with access to the KMS key can decrypt.

---

### Table: `verification_steps`

```sql
CREATE TABLE verification_steps (
    step_id             UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id      UUID            NOT NULL REFERENCES kyc_applications(application_id),
    step_type           VARCHAR(30)     NOT NULL CHECK (step_type IN ('DOCUMENT_OCR','LIVENESS','WATCHLIST_SCREENING','MANUAL_REVIEW')),
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    vendor              VARCHAR(30),
    vendor_reference_id VARCHAR(255),
    result              JSONB,           -- OcrResult, LivenessResult, WatchlistResult (vendor-specific shape)
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    retry_count         INT             NOT NULL DEFAULT 0,
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- One step of each type per application
CREATE UNIQUE INDEX idx_step_type_per_app
    ON verification_steps(application_id, step_type);

-- Query pending steps for orchestrator
CREATE INDEX idx_steps_pending ON verification_steps(application_id, status)
    WHERE status IN ('PENDING', 'IN_PROGRESS');

-- Vendor reference lookup (for webhook matching)
CREATE INDEX idx_step_vendor_ref ON verification_steps(vendor, vendor_reference_id);
```

**`result` as JSONB:** Each step type has a different result structure. JSONB allows flexible storage without schema changes when vendors add new fields. Application code maps the JSONB to typed `VerificationResult` objects.

---

### Table: `state_transitions`

```sql
CREATE TABLE state_transitions (
    transition_id       UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id      UUID            NOT NULL REFERENCES kyc_applications(application_id),
    from_status         VARCHAR(50),    -- null for initial SUBMITTED transition
    to_status           VARCHAR(50)     NOT NULL,
    trigger             VARCHAR(20)     NOT NULL CHECK (trigger IN ('SYSTEM','OPERATOR','API_CALLBACK','SCHEDULER')),
    triggered_by        VARCHAR(255)    NOT NULL,
    reason              TEXT            NOT NULL,
    occurred_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    metadata            JSONB
);

-- Audit query: all transitions for an application in order
CREATE INDEX idx_transitions_app ON state_transitions(application_id, occurred_at ASC);

-- Compliance query: all transitions to MANUAL_REVIEW in date range
CREATE INDEX idx_transitions_to_status ON state_transitions(to_status, occurred_at DESC);
```

**Never updated or deleted.** `REVOKE UPDATE, DELETE ON state_transitions FROM kyc_app_user;`

---

### Table: `document_references`

```sql
CREATE TABLE document_references (
    doc_ref_id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id      UUID            NOT NULL REFERENCES kyc_applications(application_id),
    s3_key_encrypted    BYTEA           NOT NULL,  -- Encrypted S3 object key
    s3_key_version      VARCHAR(50)     NOT NULL,
    document_type       VARCHAR(30)     NOT NULL,
    side                VARCHAR(10)     NOT NULL CHECK (side IN ('FRONT', 'BACK', 'BOTH')),
    uploaded_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    is_purged           BOOLEAN         NOT NULL DEFAULT false,
    purged_at           TIMESTAMPTZ
);

CREATE INDEX idx_docref_application ON document_references(application_id);
```

**S3 key is encrypted** — even the storage key is protected. Without decryption, an attacker with DB access cannot locate the document in S3.

---

### Table: `manual_review_queue`

```sql
CREATE TABLE manual_review_queue (
    review_id           UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id      UUID            NOT NULL REFERENCES kyc_applications(application_id) UNIQUE,
    priority            VARCHAR(10)     NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('HIGH','MEDIUM','LOW')),
    routing_reason      VARCHAR(50)     NOT NULL,  -- WATCHLIST_HIT, DOCUMENT_REJECTED, LIVENESS_FAILED, VENDOR_ERROR
    assigned_reviewer   UUID,
    assigned_at         TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    decision            VARCHAR(20),
    notes               TEXT,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- Queue fetch query (unassigned, ordered by priority then age)
CREATE INDEX idx_review_unassigned ON manual_review_queue(priority DESC, created_at ASC)
    WHERE assigned_reviewer IS NULL AND completed_at IS NULL;
```

**Priority assignment logic:**
- `HIGH`: WATCHLIST_HIT (sanctions match) — 4-hour SLA
- `MEDIUM`: Document quality issues, liveness borderline
- `LOW`: Vendor error, pending re-attempt

---

### Table: `webhook_events` (Vendor Callback Log)

```sql
CREATE TABLE webhook_events (
    webhook_id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor              VARCHAR(30)     NOT NULL,
    vendor_reference_id VARCHAR(255)    NOT NULL,
    raw_payload         JSONB           NOT NULL,
    signature_valid     BOOLEAN         NOT NULL,
    processed           BOOLEAN         NOT NULL DEFAULT false,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    processed_at        TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_webhook_vendor_ref ON webhook_events(vendor, vendor_reference_id);
CREATE INDEX idx_webhook_unprocessed ON webhook_events(created_at) WHERE processed = false;
```

Raw webhook payloads stored for audit and replay. `UNIQUE(vendor, vendor_reference_id)` prevents duplicate processing of the same callback.

---

## Indexing Strategy Summary

| Table | Key Index | Purpose |
|---|---|---|
| kyc_applications | `(user_id) WHERE active` | One active application per user enforcement |
| kyc_applications | `(user_id, status, created_at)` | Status polling by user |
| kyc_applications | `(pii_expires_at) WHERE not purged` | Retention purge scheduler |
| verification_steps | `(application_id, step_type)` | Duplicate step prevention |
| verification_steps | `(vendor, vendor_reference_id)` | Webhook-to-step matching |
| state_transitions | `(application_id, occurred_at)` | Audit trail retrieval |
| manual_review_queue | `(priority DESC, created_at) WHERE unassigned` | Queue priority ordering |

---

## Data Retention & Purge Design

### Retention Policy

| Data Type | Retention Period | Post-Retention Action |
|---|---|---|
| KYC application metadata | 5 years from approval date | Anonymize (nullify user_id reference) |
| PII (personal_data_encrypted) | 2 years from account closure | AES key deletion (crypto-erasure) |
| Document images (S3) | 2 years from account closure | S3 lifecycle → Glacier → Delete |
| State transition history | 7 years (regulatory audit requirement) | Retain, PII redacted |
| Watchlist screening results | 7 years | Retain (evidence of screening) |

### Crypto-Erasure for GDPR

When a user exercises the right to erasure:
1. Look up `personal_data_key_version` for all user's applications
2. Delete the KMS key version from AWS KMS
3. The `personal_data_encrypted` bytes remain in the DB but are permanently undecryptable
4. S3 document images deleted via AWS S3 Delete Object
5. `is_pii_purged = true`, `pii_purged_at = now()` recorded
6. State transition history remains (compliance requirement) but without PII fields

This satisfies GDPR erasure without physically deleting rows — state transitions (non-PII) are preserved for regulatory audit.

### Purge Scheduler

Spring `@Scheduled(cron = "0 2 * * *")` — runs at 2 AM daily:
1. Query: `SELECT * FROM kyc_applications WHERE pii_expires_at <= now() AND is_pii_purged = false LIMIT 1000`
2. For each application: delete KMS key, delete S3 documents, mark purged
3. Idempotent: safe to re-run if interrupted

---

## Consistency Model

| Operation | Consistency | Mechanism |
|---|---|---|
| Submit application | ACID (PostgreSQL) | Single transaction: insert application + initial state transition |
| State transition | ACID | Single transaction: update status + insert transition + update step |
| PII encryption | Application-layer | AES-256-GCM via AWS KMS before INSERT |
| Vendor result storage | ACID | Step UPDATE + state transition in one transaction |
| Manual review decision | ACID | Review decision + application state transition in one transaction |

---

## Multi-Tenancy Considerations

For a multi-tenant KYC service (serving multiple fintech products):

- `tenant_id` column added to all tables
- Row-level security in PostgreSQL: `CREATE POLICY tenant_isolation ON kyc_applications USING (tenant_id = current_setting('app.tenant_id'))`
- Separate KMS key per tenant for encryption isolation
- Separate S3 bucket prefix per tenant: `s3://kyc-documents/{tenant_id}/`

---

## Interview Discussion Points

- **Why store personal_data as encrypted BYTEA instead of individual encrypted columns?** Encrypting individual columns (name, DOB, address each encrypted separately) requires N KMS API calls per operation — expensive and slow. Encrypting the entire JSONB blob in one AES-256-GCM operation is cheaper and allows atomic data minimization (delete one blob = all PII gone)
- **How do you search for applications by name (e.g., compliance officer searching for "Piyush Prasad")?** You cannot query encrypted data. Two options: (1) store a deterministic hash of the name (HMAC-SHA256 with a stable key) as a separate indexed column — enables exact-match search, (2) accept that search requires decryption — expose a search endpoint that decrypts on the fly for authorized reviewers. Most KYC systems accept option 2 with strict access controls
- **Why UNIQUE index on (application_id) in manual_review_queue?** An application can only be in the review queue once. Without this, a race condition could insert two queue entries for the same application — two reviewers receive the same case, both make decisions, second decision fails gracefully (application already decided)
- **How do you handle the archival of state transitions for 7 years?** Partition `state_transitions` by year. After 7 years, move the partition to read-only tablespace and eventually to S3 Parquet via Athena. State transitions contain no PII directly (they reference application_id, not personal data) — no retention conflict with the shorter PII retention
