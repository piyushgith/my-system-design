# 08 — Security Design: KYC / Identity Verification Pipeline

---

## Objective

Define the security architecture for the KYC service — PII protection, access controls, vendor API security, audit logging, and regulatory compliance (GDPR, DPDP, RBI KYC Master Direction).

---

## Threat Model

| Threat | Severity | Example |
|---|---|---|
| Unauthorized PII access | Critical | Engineer directly queries personal_data_encrypted |
| Document image exfiltration | Critical | Attacker downloads S3 document images |
| Identity spoofing (fake documents) | High | Submitting a forged Aadhaar card |
| Liveness bypass (deepfake) | High | AI-generated video passes liveness check |
| Duplicate identity submission | High | Same person creates N accounts with N KYC applications |
| Insider threat (reviewer access) | High | Compliance officer exports customer PII |
| Watchlist bypass | Critical | Compromising the watchlist vendor connection |
| KYC outcome injection | Critical | Attacker forges APPROVED state transition |

---

## Authentication & Authorization

### Service-to-Service (Internal)

- mTLS between all internal services (Istio sidecar)
- JWT with short TTL (5 minutes) for service identity
- KYC service trusts only: Onboarding Service, Compliance Dashboard, Loan Service

### Manual Review Dashboard (Human Users)

- OAuth2 with PKCE + OIDC for compliance officer authentication
- MFA required (TOTP or hardware key)
- SSO via company IdP (Okta, Azure AD)
- Session TTL: 8 hours; re-auth on PII access

### Authorization Scopes

| Scope | Operations Allowed | Assigned To |
|---|---|---|
| `kyc:submit` | POST /applications | Onboarding Service |
| `kyc:read_status` | GET /applications/{id} (no PII) | All internal services |
| `kyc:review` | GET /review/queue, POST /review/{id}/decision | Compliance Officers |
| `kyc:review_pii` | GET /review/{id}/pii — access logged | Compliance Officers (with MFA) |
| `kyc:audit` | GET /applications/{id}/transitions | Audit/Legal team |
| `kyc:admin` | PII purge, vendor config, re-verification | System Admin + break-glass |
| `kyc:webhook` | POST /webhooks/{vendor} | Vendor webhook only (IP-restricted) |

---

## PII Encryption Architecture

```mermaid
graph TB
    APP[KYC Service Pod]
    KMS[AWS KMS<br/>Customer-Managed Key]
    DB[(PostgreSQL<br/>personal_data_encrypted BYTEA)]
    S3[(S3<br/>encrypted documents)]

    APP -->|GenerateDataKey| KMS
    KMS -->|DataKey plaintext + ciphertext| APP
    APP -->|AES-256-GCM encrypt(PII, datakey_plaintext)| APP
    APP -->|Store: ciphertext + encrypted_datakey| DB
    APP -->|Delete datakey_plaintext from memory| APP

    Note: S3 server-side encryption (SSE-KMS) is separate
    APP -->|PutObject with SSE-KMS| S3
```

**Envelope Encryption Pattern:**
1. KMS generates a Data Encryption Key (DEK) — 256-bit
2. KMS returns plaintext DEK + KMS-encrypted DEK (ciphertext)
3. Application encrypts PII blob with plaintext DEK (AES-256-GCM in memory)
4. Store: encrypted PII blob + KMS-encrypted DEK in the database row
5. Immediately discard plaintext DEK from memory
6. For decryption: call KMS to decrypt the stored DEK → use to decrypt PII

**Why envelope encryption?** KMS can only encrypt data up to 4 KB directly. PII blobs can be larger. Envelope encryption uses KMS for key management but AES-GCM for actual data encryption — unlimited data size, KMS only manages the small key.

**Per-application key:** Each `kyc_application` has its own DEK. Purging a user's data = deleting their DEK from KMS. All encrypted blobs become permanently irrecoverable.

---

## Document Security

### S3 Security Controls

- S3 bucket: private — no public access
- Server-side encryption: SSE-KMS (AWS managed key at S3 level, separate from PII encryption)
- Bucket policy: deny all access except KYC service IAM role
- S3 access logging: every GET/PUT/DELETE logged to a separate audit bucket
- VPC Endpoint: all S3 traffic stays within AWS VPC — never traverses public internet
- Presigned URL constraints:
  - Expires after 5 minutes
  - Content-Type enforced: only `image/jpeg`, `image/png`, `application/pdf`
  - MaxFileSize: 5 MB per document
  - Scoped to specific object key — cannot overwrite other objects

### Document Access Audit

Every time the KYC service fetches a document from S3 (for vendor processing), the access is logged:

```json
{
  "event": "DOCUMENT_FETCHED",
  "application_id": "kyc_app_uuid",
  "document_key": "kyc-docs/enc/usr_abc123/doc_uuid.jpg",
  "fetched_by": "kyc-pipeline",
  "purpose": "DOCUMENT_OCR",
  "fetched_at": "2024-01-15T10:30:05Z"
}
```

This satisfies DPDP requirement: maintain a log of all personal data processing activities.

---

## Vendor API Security

### Outbound Calls to Vendors

- All vendor API calls via HTTPS TLS 1.3
- Vendor API keys stored in AWS Secrets Manager — never in application config or environment variables
- API key rotation: every 90 days for Onfido/LexisNexis, quarterly for DigiLocker
- Request timeout: 10 seconds — never block indefinitely on vendor
- IP allowlist: vendor API calls originate from static NAT gateway IPs — vendors can verify source

### Webhook Security (Inbound from Vendors)

- Webhook endpoint: IP-restricted to vendor's published IP ranges
- HMAC-SHA256 signature validation on every webhook request
- Signature key stored in AWS Secrets Manager, rotated quarterly
- Request size limit: 1 MB — prevent payload abuse
- Idempotency: `vendor_reference_id` deduplicated — re-delivered webhooks ignored

---

## Preventing Identity Fraud

### Duplicate Identity Detection

A user should not be able to create N accounts using N KYC applications. After OCR extracts the document ID number (Aadhaar UID, PAN, Passport number):

1. Hash the document ID: `HMAC-SHA256(doc_id, stable_secret_key)` → produce a deterministic pseudonymous token
2. Store this token in `verification_steps.result.document_id_hash` (not the raw ID)
3. UNIQUE constraint on `(document_type, document_id_hash)` across all APPROVED applications
4. A second application with the same document → constraint violation → rejected as `DUPLICATE_IDENTITY`

**Why hash, not store raw?** The raw Aadhaar UID is government PII. Storing it directly increases regulatory scope. A deterministic HMAC allows duplicate detection without retaining the raw value.

### Liveness Anti-Spoofing

- Always use vendor liveness checks with active challenge (blink, turn head) — not passive (static photo check)
- If liveness confidence < 0.90: route to manual review, not auto-reject (some genuine users fail active liveness due to accessibility issues)
- Log spoofing attempts: liveness check with spoof_type != null → log IP, device fingerprint, and application_id for fraud analysis

---

## Audit Logging

Every security-relevant event is logged to an append-only audit system (separate from PostgreSQL):

| Event | Fields |
|---|---|
| KYC application submitted | application_id, user_id, caller_service, ip_address, timestamp |
| PII accessed by reviewer | application_id, reviewer_id, purpose, timestamp |
| State transition | application_id, from_state, to_state, triggered_by, timestamp |
| Document fetched | application_id, document_key, fetched_by, purpose |
| Vendor webhook received | vendor, signature_valid, reference_id, timestamp |
| Watchlist hit found | application_id, list_name, match_score, timestamp |
| PII purged | application_id, purge_trigger, timestamp |
| Authorization failure | caller, scope_required, endpoint, timestamp |

Audit logs → append-only Kafka topic → S3 sink → 10-year retention.

---

## Compliance: RBI KYC Master Direction

| Requirement | Implementation |
|---|---|
| Customer Due Diligence (CDD) | Document OCR + liveness + watchlist for all new accounts |
| Periodic KYC update | Re-verification triggered every 2 years for low-risk, every year for high-risk |
| Watchlist screening | LexisNexis screens against OFAC, UN Consolidated List, domestic lists |
| Record keeping | State transitions retained 7 years; document images retained 2 years after account closure |
| Audit trail | Immutable state_transitions with operator identity |
| Risk-based approach (RBA) | KYC tier (BASIC/STANDARD/FULL) based on risk score from Risk Engine |

---

## Interview Discussion Points

- **How do you ensure a compromised engineer cannot read customer PII?** Envelope encryption: even with DB access, an engineer sees only encrypted bytes. They would need AWS KMS access to decrypt — KMS access is controlled by IAM policies with MFA requirement. All KMS calls are logged in CloudTrail. Any unauthorized decrypt attempt triggers a security alert
- **How do you handle GDPR right to erasure for KYC records?** Crypto-erasure: delete the per-application KMS key. The encrypted PII bytes remain in the DB but are permanently irrecoverable. State transitions (non-PII) are retained for regulatory compliance. Document images are deleted from S3. This satisfies GDPR erasure without violating the financial record retention obligation
- **What if the vendor sends a fake APPROVED webhook?** Webhook signature validation: every webhook is validated against HMAC-SHA256 signature using the vendor's shared secret. A forged webhook fails signature validation → rejected + security alert. The KYC state machine also validates that the transition is legal from the current state — an out-of-order webhook cannot bypass steps
- **How do you prevent the same person from creating multiple accounts with different documents?** This is harder. If someone uses one Aadhaar and one PAN with two different phone numbers, the document hash check on each type would not detect the cross-type duplicate. Solution: biometric deduplication — compare facial embeddings from the selfie against all existing embeddings. If similarity > threshold → flag as potential duplicate for manual review
