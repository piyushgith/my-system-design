# 08 — Security Design: Loan Origination & Servicing System

## Objective

Define authentication, authorization, data protection, and regulatory compliance controls for a lending platform handling PII and financial transactions.

---

## Threat Model

| Threat | Risk | Control |
|--------|------|---------|
| Unauthorized access to another borrower's loan | Data breach, financial fraud | Strict participant-scoped authorization |
| Maker approving their own checker decision | Regulatory non-compliance | System enforces `maker_id != checker_id` |
| Duplicate EMI debit (double charging) | Financial harm, customer trust | Idempotency key on repayment recording |
| Forged credit bureau report | Loan fraud | Bureau reports fetched directly by system, never from client |
| PII exfiltration (Aadhaar, PAN, income data) | Regulatory fine (DPDP Act), identity theft | Encryption at rest, column-level masking, access audit |
| Fraudulent disbursement (redirect funds) | Direct financial loss | Bank account verified against KYC before disbursement |
| Admin privilege escalation | System-wide fraud | PAM (Privileged Access Management), break-glass procedures |
| Insider data access (loan officer accessing data without business need) | Regulatory violation | Row-level security, access log audit |

---

## Authentication

### Borrower Authentication

- OAuth 2.0 Authorization Code Flow + PKCE (for web/mobile clients)
- JWT access tokens (15-minute expiry) + refresh tokens (7-day expiry, rotating)
- Mobile: biometric authentication tied to device session
- Re-authentication required for sensitive operations (offer acceptance, bank account change)

### Loan Officer / Back-Office Authentication

- Corporate SSO (SAML 2.0 / OAuth 2.0 via Azure AD / Okta)
- MFA mandatory for all back-office users
- Session timeout: 8 hours (extended via activity), 30 minutes idle
- IP allowlist: back-office access restricted to corporate network / VPN

### Internal Service Authentication

- mTLS via service mesh (Istio)
- Vault-issued short-lived certificates (24-hour TTL, auto-renewed)
- Service accounts per deployment — no human credentials used by services

---

## Authorization: RBAC Model

| Role | Permissions |
|------|------------|
| BORROWER | View own applications and loans, submit documents, accept offer |
| LOAN_OFFICER | View applications, create applications on behalf of borrower (assisted mode), no approval |
| UNDERWRITER_MAKER | View applications, submit credit decision (cannot be checker for same application) |
| UNDERWRITER_CHECKER | Approve/reject maker decisions (cannot be maker for same application) |
| SENIOR_UNDERWRITER | Checker for high-value (> ₹25 lakh) applications |
| COLLECTIONS_AGENT | View assigned overdue loan accounts, create payment arrangements |
| RISK_MANAGER | View all loan performance data, update credit policies |
| COMPLIANCE_OFFICER | Read-only access to all events, audit logs, regulatory reports |
| FINANCE_ADMIN | View ledger, approve write-offs |
| SYSTEM_ADMIN | User management, role assignment (not loan data access) |

### Row-Level Security

PostgreSQL Row Level Security (RLS) enforces data isolation:

```sql
-- Borrowers can only see their own data
CREATE POLICY borrower_isolation ON loan_accounts
  USING (borrower_id = current_setting('app.current_user_id')::uuid);

-- Collections agents only see assigned accounts
CREATE POLICY collections_assignment ON loan_accounts
  USING (
    EXISTS (
      SELECT 1 FROM collections_assignments ca
      WHERE ca.loan_account_id = loan_accounts.loan_account_id
        AND ca.agent_id = current_setting('app.current_user_id')::uuid
    )
  );
```

RLS is enforced at DB level — bypasses application bugs that might expose cross-borrower data.

### Maker-Checker Enforcement

```sql
-- Enforced at DB level (not just application)
CONSTRAINT maker_checker_different CHECK (maker_id IS NULL OR checker_id IS NULL OR maker_id != checker_id)
```

Application-level check: when assigning checker, verify checker's team is different from maker's team (four-eyes principle).

---

## Data Protection

### PII Encryption

| Field | Storage | Access |
|-------|---------|--------|
| Aadhaar number | SHA-256 hash only (never raw) | Lookup only, cannot reverse |
| PAN number | Encrypted (AES-256-GCM, application-level) | Loan officers with need |
| Bank account number | Last 4 digits in plain + encrypted full | Finance admin only |
| Income data | Encrypted | Underwriters only, audit-logged |
| Credit bureau report | Encrypted JSON | Underwriters + SYSTEM |
| Full name, mobile, email | Plain text | Loan officers, borrower's own view |

**Application-level encryption** using key stored in HashiCorp Vault. Database cannot read encrypted fields without application-level decryption — protects against DB-level breach.

### Data Masking in Logs

Log pipeline (Logstash / Fluentd) applies pattern-matching masks before logs reach ELK:
- PAN format: mask to `ABCDE****F`
- Mobile: mask to `98765*****`
- Loan amount: masked for operations logs (retained for audit log only)

### GDPR / DPDP Compliance

India's Digital Personal Data Protection Act (2023):

| Right | Implementation |
|-------|--------------|
| Right to erasure | Soft-delete + PII anonymization for inactive accounts |
| Right to access | API: `GET /v1/my-data` returns all stored data |
| Consent management | Explicit consent captured at application with timestamp |
| Data minimization | Only collect fields needed for credit decision |
| Retention limits | PII deleted after regulatory retention period |

**Conflict with regulatory retention:** Loan records must be retained 10 years (RBI). DPDP erasure applies to PII (name, Aadhaar, mobile) but NOT to financial transaction records. Solution: pseudonymize PII on erasure request — replace with anonymized identifiers, retain financial history.

---

## Secrets Management

HashiCorp Vault:
- Database credentials: dynamic secrets (Vault generates per-service credentials with TTL)
- API keys (CIBIL, NACH, bank): stored in Vault KV with versioning
- Encryption keys for PII: Vault Transit engine (encryption as a service — application never handles raw keys)
- JWT signing keys: Vault PKI, rotated every 30 days

**No secrets in:** environment variables, Kubernetes manifests, config files, version control.

Vault Agent sidecar injects secrets into application pod at runtime.

---

## Financial Controls

### Disbursement Account Verification

Before disbursement:
1. Verify bank account belongs to borrower (penny-drop verification: send ₹1, verify returned account holder name matches borrower name)
2. KYC check: disbursement account must be registered in borrower's name
3. No cross-party disbursement (can't disburse to third party)

Penny-drop is mandatory — prevents fraudulent bank account change attacks.

### Write-Off Authorization

Write-offs require:
1. Collections case with evidence of recovery exhaustion
2. Finance Admin (Maker) initiates write-off
3. CFO-level approval (Checker) for amounts > ₹50,000
4. Audit trail with all collection attempts documented

Write-off creates ledger entries — full traceability.

---

## Network Security

```
Internet
    │
    ▼
WAF (AWS WAF / Cloudflare) — OWASP Top 10 rules, rate limiting
    │
    ▼
API Gateway (Spring Cloud Gateway) — authentication, routing
    │
    ▼
Application Services (Private subnet) — no direct internet
    │
    ▼
Data Layer (Isolated subnet) — PostgreSQL, Redis
    │
    ▼
External Integrations via PrivateLink / VPN (CIBIL, bank API)
```

External bureau and bank API connections via AWS PrivateLink or dedicated VPN — not over public internet.

---

## Spring Security Configuration

```
Key annotations used:
- @PreAuthorize("hasRole('UNDERWRITER_MAKER') and @taskSecurityService.isNotMaker(#taskId, authentication)")
  → Prevents underwriter from both making AND checking the same task
  
- @PreAuthorize("@loanSecurityService.canAccessLoan(#loanAccountId, authentication)")
  → Borrower can only access their own loan (checked against JWT's borrowerI)
  
- @PreAuthorize("hasRole('FINANCE_ADMIN') and @writeOffService.isWithinLimit(#amount) or hasRole('CFO')")
  → Tiered authorization for write-offs
```

CSRF: disabled for REST API (stateless JWT). Re-enabled for any server-rendered admin UI (separate concern).

---

## Audit Logging for Financial Operations

Every operation logged with:
- Actor (userId + role)
- Resource (loanAccountId, applicationId)
- Action
- Old state → New state
- IP address
- Correlation ID (distributed trace)
- Timestamp

**Immutable guarantee:** audit log stored append-only. PostgreSQL RLS prevents UPDATE/DELETE on audit_log table for all roles. Separate compliance DB replica for regulatory access.

**Regulatory retention:** 10 years for all financial audit records (RBI guidelines).
