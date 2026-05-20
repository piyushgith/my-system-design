# 08 — Security Design: Double-Entry Ledger Service

---

## Objective

Define the security architecture for the double-entry ledger — authentication, authorization, encryption, audit, and compliance controls. The ledger is a financial source of truth; security failures here mean financial fraud or regulatory violation.

---

## Threat Model

| Threat | Severity | Example |
|---|---|---|
| Duplicate posting (replay attack) | Critical | Replay a valid payment POST → double debit |
| Unauthorized balance read | High | Attacker reads competitor's account balance |
| Unauthorized posting creation | Critical | Attacker credits their own account |
| Journal entry manipulation | Critical | Attacker modifies posted entries to change balances |
| SQL injection in metadata/description | High | Modify journal entries via unvalidated input |
| Insider access (privileged engineer) | High | DBA directly modifies journal_entries rows |
| Denial of service on posting API | Medium | Saturate posting API, block payment processing |
| Stale balance exploit | Medium | Read stale cached balance to bypass limit check |

---

## Authentication

### Service-to-Service: mTLS + JWT

The ledger is an internal service — no end-user access. All callers are internal services.

**mTLS (Mutual TLS):**
- All internal services present client certificates issued by the internal CA
- Ledger validates client cert before accepting any request
- Prevents: unauthorized service impersonation, network-level eavesdropping

**JWT for identity propagation:**
- JWT issued by central auth service to each internal service
- JWT claims: `{ "service_id": "payment-service", "scopes": ["ledger:post", "ledger:read_balance"] }`
- Ledger validates JWT signature using auth service's public key (rotated quarterly)
- Short JWT TTL: 5 minutes — limits blast radius of a compromised token

**No user-facing authentication:** The ledger never accepts requests from end users. The payment/wallet service authenticates the end user, then calls the ledger with its own service identity.

---

## Authorization: RBAC + Scope-Based

| Scope | Allowed Operations | Assigned To |
|---|---|---|
| `ledger:post` | POST /postings | Payment, Wallet, Loan, Card services |
| `ledger:reverse` | POST /postings/{id}/reverse | Payment service only (with additional approval flow) |
| `ledger:read_balance` | GET /accounts/{id}/balance | All internal services |
| `ledger:read_statement` | GET /postings?account_id=... | Finance ops, Audit service |
| `ledger:manage_accounts` | POST/PATCH /accounts | Account management service only |
| `ledger:reconcile` | POST /reconciliation/runs | Finance ops, Compliance team |
| `ledger:admin` | All operations | SRE/DBA with break-glass procedure |

**Authorization enforcement:**
- Spring Security: `@PreAuthorize("hasScope('ledger:post')")` on controller methods
- Scope checked on every request — no caching of authorization decisions
- All authorization failures logged with full request context for audit

---

## Data Security

### Encryption at Rest

| Data | Encryption | Key Management |
|---|---|---|
| PostgreSQL data files | AES-256 (PostgreSQL TDE or disk-level) | AWS KMS, key rotation every 90 days |
| Redis cluster | AES-256-GCM (Redis 7.x at-rest encryption) | AWS KMS |
| S3 archived journal partitions | SSE-S3 (AES-256) with customer-managed keys | AWS KMS CMK per environment |
| Backup files | AES-256 | Separate KMS key for backups |

### Encryption in Transit

- All internal gRPC calls: TLS 1.3 minimum
- All REST API calls: TLS 1.3
- PostgreSQL connections: `sslmode=require` — no plaintext DB connections permitted
- Kafka: TLS + SASL_SSL for broker connections
- Redis: TLS for client-to-node and node-to-node traffic

### Sensitive Fields

Journal entries and postings may carry `metadata` that includes PII (e.g., `initiated_by: user_id`). Metadata values are not indexed and stored as opaque JSONB. At PII review checkpoints, metadata fields are evaluated for data minimization compliance.

**No raw PAN, CVV, or account numbers stored in the ledger.** Those live in the card vault (PCI-DSS scope). The ledger uses internal `account_id` (UUID) as the reference — never the actual card or bank account number.

---

## Database Security

### Row-Level Security

Application user has restricted privileges — cannot modify the journal:

```sql
-- Ledger app DB user
CREATE ROLE ledger_app LOGIN;
GRANT SELECT, INSERT ON journal_entries TO ledger_app;
GRANT SELECT, INSERT, UPDATE ON postings TO ledger_app;      -- UPDATE only for status field
GRANT SELECT, INSERT, UPDATE ON account_snapshots TO ledger_app;
GRANT SELECT, INSERT, UPDATE ON outbox_events TO ledger_app;
REVOKE DELETE ON ALL TABLES IN SCHEMA ledger FROM ledger_app;

-- Admin user (for break-glass access only)
CREATE ROLE ledger_admin;
GRANT ALL ON ALL TABLES IN SCHEMA ledger TO ledger_admin;
-- ledger_admin access requires MFA + session recording (via bastion)
```

### Audit Triggers

Any attempt to UPDATE or DELETE `journal_entries` generates an alert:

```sql
CREATE RULE no_update_journal AS ON UPDATE TO journal_entries
    DO INSTEAD NOTHING;  -- Silently block UPDATE

CREATE RULE no_delete_journal AS ON DELETE TO journal_entries
    DO INSTEAD NOTHING;  -- Silently block DELETE

-- Alternatively: audit trigger that logs the attempt to a separate audit_violations table
```

### SQL Injection Prevention

- All queries use JPA/Hibernate with named parameters — no string concatenation in queries
- `metadata` JSONB fields: input validated and sanitized before storage
- Search parameters (e.g., reference_type filter) validated against allowlist of permitted values

---

## Idempotency as a Security Control

Idempotency prevents replay attacks. An attacker who intercepts a valid posting request and replays it cannot create a duplicate posting:

1. First request: creates posting, stores `idempotency_key` → `posting_id` mapping
2. Replay: Redis returns existing `posting_id` → returns original response, no new journal entries

**Idempotency key scoping prevents cross-caller injection:**
- Keys are namespaced by authenticated `service_id` at the application layer
- Key format: `{service_id}:{reference_type}:{reference_id}`
- Payment service cannot use a key that Wallet service already used — namespacing enforced

---

## Audit Logging

Every security-relevant event is logged in a tamper-resistant audit log:

| Event | Fields Logged |
|---|---|
| Posting created | posting_id, caller_service, reference_type, all account_ids, amounts, timestamp |
| Posting reversed | reversal_posting_id, original_posting_id, reason, caller_service, timestamp |
| Account frozen | account_id, admin_user, reason, timestamp |
| Authorization failure | caller_service, scope_required, endpoint, timestamp, request_id |
| Balance read (sensitive accounts) | account_id, caller_service, balance_returned, timestamp |
| Admin login | admin_user, IP, timestamp, session_id |

**Audit log storage:**
- Append-only Kafka topic: `ledger.audit.events` → S3 (long-term retention, 10 years)
- Audit log is NOT in the same PostgreSQL database — isolated from application data
- SIEMs (Splunk, ELK) subscribe to audit Kafka topic for real-time alerting

---

## Secrets Management

| Secret | Storage | Rotation |
|---|---|---|
| DB credentials | AWS Secrets Manager | Every 30 days (automatic rotation) |
| Redis auth token | AWS Secrets Manager | Every 90 days |
| JWT signing key (auth service) | AWS Secrets Manager + KMS | Every 90 days |
| Kafka SASL credentials | AWS Secrets Manager | Every 90 days |
| TLS certificates | AWS ACM | Auto-renewed |

Spring Boot retrieves secrets at startup via AWS Secrets Manager SDK — no secrets in environment variables or Kubernetes ConfigMaps.

---

## Rate Limiting as Security

| Attack Vector | Mitigation |
|---|---|
| Brute-force balance enumeration | Rate limit GET /balance: 1000 RPS per service |
| DDoS on posting API | API Gateway WAF + rate limiting by IP/service |
| Idempotency key exhaustion | Keys expire after 24h; key format validation rejects malformed keys |
| Bulk unauthorized reversal | `ledger:reverse` scope restricted; reversal requires original posting ownership check |

---

## Compliance Considerations

| Regulation | Requirement | Implementation |
|---|---|---|
| RBI IT Framework | Audit trail for all financial transactions | Immutable journal + audit Kafka topic |
| PCI-DSS | No card data in non-PCI systems | Ledger uses internal account UUIDs only — zero PAN exposure |
| GDPR / DPDP | Right to erasure | Cannot delete journal entries — anonymize metadata fields instead (replace PII with `REDACTED:hash`) |
| SOC 2 Type II | Access controls, change management | RBAC + DB privileges + break-glass audit |

---

## Interview Discussion Points

- **How do you prevent a rogue engineer from directly modifying journal entries?** PostgreSQL REVOKE on UPDATE/DELETE for the application role. Admin role requires MFA + session recording via database activity monitoring (AWS RDS IAM auth + CloudTrail). Any direct modification generates an audit alert
- **How do you handle GDPR right-to-erasure for journal entries?** The journal is legally required to be retained (financial regulation > GDPR in most cases for transactional data). For metadata PII within journal entries, pseudonymize — replace `user_id` in metadata with a reversible encrypted token; if erasure is required, delete the decryption key (crypto-erasure)
- **How does mTLS work in a Kubernetes environment?** Each pod gets a short-lived certificate from a service mesh CA (Istio, Linkerd). The service mesh sidecar terminates and re-establishes mTLS transparently. Application code does not handle TLS — it speaks plain HTTP internally; the mesh layer provides mTLS
- **How do you detect if someone is enumerating account balances?** API Gateway tracks `GET /accounts/{id}/balance` calls per service per second. Anomaly detection: any service querying more than 10,000 distinct account IDs per hour triggers a security alert — normal balance reads are targeted, not exploratory
