# 05 — Database Design: Loan Origination & Servicing System

## Objective

Design the relational schema for a lending platform. Strong consistency (ACID) is non-negotiable for financial data. Schema must support multi-year loan lifecycle, audit history, and efficient query patterns for servicing.

---

## Why PostgreSQL?

- ACID transactions essential for ledger operations and EMI reconciliation
- Relational model is natural for loan schedule, repayment history (structured, relational data)
- `JSONB` for flexible document metadata and underwriting payload
- `TIMESTAMPTZ` for multi-timezone compliance (all stored in UTC)
- Row-level security for data isolation between loan officer roles
- Mature tooling: pgBouncer, pg_partman, logical replication

**When to add other storage:**
- S3: document files (PDFs, images) — never store binary in PostgreSQL
- Redis: caching loan summaries, rate cards, idempotency keys
- Elasticsearch: full-text search on borrower name, application data for back-office

---

## Schema Design

### Borrower Context

```sql
CREATE TABLE borrowers (
    borrower_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id         VARCHAR(64) UNIQUE NOT NULL,  -- from identity provider
    full_name           VARCHAR(256) NOT NULL,
    date_of_birth       DATE NOT NULL,
    pan_number          VARCHAR(10) UNIQUE,
    aadhaar_hash        VARCHAR(64),  -- SHA-256 of masked Aadhaar — never store raw
    mobile_number       VARCHAR(15),
    email               VARCHAR(256),
    kyc_status          VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    kyc_completed_at    TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_deleted          BOOLEAN DEFAULT FALSE,  -- GDPR soft delete
    version             BIGINT NOT NULL DEFAULT 0
);
```

### Loan Applications

```sql
CREATE TABLE loan_applications (
    application_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    borrower_id             UUID NOT NULL REFERENCES borrowers(borrower_id),
    product_type            VARCHAR(32) NOT NULL,
    status                  VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    requested_amount        NUMERIC(18,2) NOT NULL,
    requested_tenure_months INT NOT NULL,
    purpose                 VARCHAR(64),
    monthly_income          NUMERIC(18,2),
    submitted_at            TIMESTAMPTZ,
    decided_at              TIMESTAMPTZ,
    expiry_at               TIMESTAMPTZ,  -- offer validity
    rejection_reason        VARCHAR(256),
    underwriting_payload    JSONB,  -- bureau score, DTI, rule results
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_applications_borrower ON loan_applications (borrower_id);
CREATE INDEX idx_applications_status ON loan_applications (status, submitted_at);
CREATE INDEX idx_applications_decided ON loan_applications (decided_at) WHERE decided_at IS NOT NULL;
```

### Application Documents

```sql
CREATE TABLE application_documents (
    document_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id  UUID NOT NULL REFERENCES loan_applications(application_id),
    document_type   VARCHAR(32) NOT NULL,
    s3_bucket       VARCHAR(128) NOT NULL,
    s3_key          VARCHAR(512) NOT NULL,
    file_size_bytes BIGINT,
    mime_type       VARCHAR(64),
    status          VARCHAR(24) NOT NULL DEFAULT 'UPLOADED',  -- UPLOADED, VERIFIED, REJECTED
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_by     UUID,
    verified_at     TIMESTAMPTZ
);

CREATE INDEX idx_documents_application ON application_documents (application_id);
```

### Maker-Checker Tasks

```sql
CREATE TABLE maker_checker_tasks (
    task_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type     VARCHAR(64) NOT NULL,    -- LOAN_APPLICATION, LOAN_RESTRUCTURING
    entity_id       UUID NOT NULL,
    action          VARCHAR(64) NOT NULL,    -- CREDIT_APPROVAL, RESTRUCTURING_APPROVAL
    amount          NUMERIC(18,2),
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING_MAKER',
    maker_id        UUID,
    maker_decision  VARCHAR(16),             -- APPROVE, REJECT
    maker_notes     TEXT,
    maker_decided_at TIMESTAMPTZ,
    checker_id      UUID,
    checker_decision VARCHAR(16),
    checker_notes   TEXT,
    checker_decided_at TIMESTAMPTZ,
    sla_deadline    TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT maker_checker_different CHECK (maker_id != checker_id)
);

CREATE INDEX idx_mkr_tasks_status ON maker_checker_tasks (status, sla_deadline);
CREATE INDEX idx_mkr_tasks_entity ON maker_checker_tasks (entity_type, entity_id);
CREATE INDEX idx_mkr_tasks_maker ON maker_checker_tasks (maker_id, status);
CREATE INDEX idx_mkr_tasks_checker ON maker_checker_tasks (checker_id, status);
```

### Loan Offers

```sql
CREATE TABLE loan_offers (
    offer_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id      UUID NOT NULL REFERENCES loan_applications(application_id),
    status              VARCHAR(24) NOT NULL DEFAULT 'EXTENDED',
    approved_amount     NUMERIC(18,2) NOT NULL,
    interest_rate       NUMERIC(6,4) NOT NULL,  -- annual rate, e.g., 0.1250 = 12.5%
    tenure_months       INT NOT NULL,
    emi_amount          NUMERIC(18,2) NOT NULL,
    processing_fee      NUMERIC(18,2),
    valid_until         TIMESTAMPTZ NOT NULL,
    accepted_at         TIMESTAMPTZ,
    disbursement_account_number VARCHAR(18),
    disbursement_ifsc   VARCHAR(11),
    nach_consent        BOOLEAN DEFAULT FALSE,
    esign_reference     VARCHAR(128),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_offers_application ON loan_offers (application_id);
CREATE INDEX idx_offers_expiry ON loan_offers (valid_until) WHERE status = 'EXTENDED';
```

### Loan Accounts

```sql
CREATE TABLE loan_accounts (
    loan_account_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_account_number     VARCHAR(32) UNIQUE NOT NULL,
    application_id          UUID NOT NULL REFERENCES loan_applications(application_id),
    borrower_id             UUID NOT NULL REFERENCES borrowers(borrower_id),
    product_type            VARCHAR(32) NOT NULL,
    status                  VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    original_principal      NUMERIC(18,2) NOT NULL,
    outstanding_principal   NUMERIC(18,2) NOT NULL,
    interest_rate           NUMERIC(6,4) NOT NULL,
    rate_type               VARCHAR(8) NOT NULL DEFAULT 'FIXED',  -- FIXED, FLOATING
    tenure_months           INT NOT NULL,
    remaining_tenure_months INT NOT NULL,
    disbursed_at            TIMESTAMPTZ NOT NULL,
    first_due_date          DATE NOT NULL,
    next_due_date           DATE,
    emi_amount              NUMERIC(18,2) NOT NULL,
    nach_mandate_id         VARCHAR(128),
    dpd                     INT NOT NULL DEFAULT 0,
    npa_classified_at       TIMESTAMPTZ,
    closed_at               TIMESTAMPTZ,
    closure_reason          VARCHAR(64),  -- FULL_REPAYMENT, WRITE_OFF, FORECLOSURE
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_loans_borrower ON loan_accounts (borrower_id, status);
CREATE INDEX idx_loans_status ON loan_accounts (status);
CREATE INDEX idx_loans_next_due ON loan_accounts (next_due_date) WHERE status = 'ACTIVE';
CREATE INDEX idx_loans_dpd ON loan_accounts (dpd) WHERE status = 'ACTIVE' AND dpd > 0;
```

### Amortization Schedule

```sql
CREATE TABLE amortization_schedule (
    id                  BIGSERIAL PRIMARY KEY,
    loan_account_id     UUID NOT NULL REFERENCES loan_accounts(loan_account_id),
    installment_number  INT NOT NULL,
    due_date            DATE NOT NULL,
    opening_principal   NUMERIC(18,2) NOT NULL,
    emi_amount          NUMERIC(18,2) NOT NULL,
    principal_component NUMERIC(18,2) NOT NULL,
    interest_component  NUMERIC(18,2) NOT NULL,
    closing_principal   NUMERIC(18,2) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
    UNIQUE (loan_account_id, installment_number)
);

CREATE INDEX idx_schedule_loan ON amortization_schedule (loan_account_id, due_date);
CREATE INDEX idx_schedule_due ON amortization_schedule (due_date, status)
    WHERE status IN ('SCHEDULED', 'PARTIAL');
```

### Repayment Records

```sql
CREATE TABLE repayment_records (
    repayment_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_account_id         UUID NOT NULL REFERENCES loan_accounts(loan_account_id),
    installment_number      INT,
    amount                  NUMERIC(18,2) NOT NULL,
    principal_paid          NUMERIC(18,2) NOT NULL,
    interest_paid           NUMERIC(18,2) NOT NULL,
    penalty_paid            NUMERIC(18,2) NOT NULL DEFAULT 0,
    payment_method          VARCHAR(24) NOT NULL,
    payment_reference       VARCHAR(128),
    source                  VARCHAR(24) NOT NULL,
    received_at             TIMESTAMPTZ NOT NULL,
    applied_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    idempotency_key         VARCHAR(128) UNIQUE,  -- prevents double application
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_repayments_loan ON repayment_records (loan_account_id, received_at);
```

### Disbursement Saga State

```sql
CREATE TABLE disbursement_sagas (
    saga_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    offer_id            UUID NOT NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'INITIATED',
    -- Steps
    ledger_reserved_at  TIMESTAMPTZ,
    bank_transfer_ref   VARCHAR(128),
    bank_transfer_at    TIMESTAMPTZ,
    bank_confirmed_at   TIMESTAMPTZ,
    loan_activated_at   TIMESTAMPTZ,
    -- Compensation
    compensated_at      TIMESTAMPTZ,
    failure_reason      TEXT,
    -- Idempotency
    idempotency_key     VARCHAR(128) UNIQUE NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

---

## Partitioning Strategy

| Table | Strategy | Benefit |
|-------|---------|---------|
| `loan_applications` | Range by `created_at` (yearly) | Archive old applications |
| `repayment_records` | Range by `received_at` (yearly) | Archive old payments |
| `amortization_schedule` | Range by `due_date` (yearly) | Query only current year |
| `loan_accounts` | None (< 10M rows manageable) | Simplicity |

---

## Indexing Strategy

| Table | Key Index | Purpose |
|-------|-----------|---------|
| loan_accounts | `(next_due_date) WHERE status = 'ACTIVE'` | EMI batch job: find due loans |
| loan_accounts | `(dpd) WHERE dpd > 0` | Collections: find overdue loans |
| amortization_schedule | `(due_date, status) WHERE status IN ('SCHEDULED')` | Daily EMI trigger |
| repayment_records | `idempotency_key` | Prevent duplicate payment application |

---

## Soft Delete Strategy

- **Borrowers:** `is_deleted = TRUE` (GDPR erasure — anonymize PII, retain skeleton)
- **Applications:** Never deleted — regulatory requirement
- **Loan accounts:** Never deleted — 10-year retention
- **Documents:** S3 files deleted after retention period; metadata row retained

---

## Audit/History Strategy

Every state-changing operation appends to `audit_log`:

```sql
CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    entity_type     VARCHAR(64) NOT NULL,
    entity_id       UUID NOT NULL,
    action          VARCHAR(64) NOT NULL,
    actor_id        UUID,
    actor_type      VARCHAR(32),  -- USER, SYSTEM, EXTERNAL_API
    old_status      VARCHAR(32),
    new_status      VARCHAR(32),
    change_payload  JSONB,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    correlation_id  VARCHAR(64)  -- trace ID
) PARTITION BY RANGE (occurred_at);

CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id, occurred_at);
```

**Append-only:** no UPDATE or DELETE ever on this table. PostgreSQL row-level security prevents even admin from modifying.

---

## Read Replicas

| Replica | Consumers | Lag Tolerance |
|---------|-----------|--------------|
| Read Replica 1 | Borrower portal (status queries, schedule view) | < 5 seconds |
| Read Replica 2 | Back-office dashboard, reporting queries | < 30 seconds |
| Primary | All writes, EMI batch job (must see latest state) | N/A |

EMI batch job reads from primary (cannot read stale `next_due_date` — would miss or double-process EMIs).
