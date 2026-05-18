# 08 — Security Design: Banking Core System

---

## Objective

Define security architecture for a banking system handling regulated financial data — the highest-security requirement category in enterprise software. Covers authentication, authorization, data protection, maker-checker security, audit requirements, and regulatory compliance.

---

## Threat Model

| Threat | Impact | Mitigation |
|---|---|---|
| Unauthorized fund transfer | Financial loss | Maker-checker, MFA, anomaly detection |
| Account takeover | Access to all customer funds | MFA, step-up auth, device binding |
| Insider fraud | Rogue employee transfers | Separation of duties, RBAC, audit logging |
| SQL injection on account data | Data breach, regulatory penalty | Parameterized queries, ORM |
| Privilege escalation | Admin access to customer accounts | RBAC, least privilege, PAM |
| Regulatory data breach | DPDP Act penalties, RBI action | Encryption, access control, data masking |
| System manipulation | Override checker approval | Cryptographic approval signatures |
| API replay attack | Replay old approvals | Nonce + timestamp + signature |

---

## Authentication

### Customer Authentication

**Retail banking mobile/web**:
- Username + Password (bcrypt, cost 14 for banking — higher than e-commerce)
- MFA mandatory for all customers (not optional):
  - TOTP app (Google Authenticator) — preferred
  - OTP via registered mobile number — fallback
  - OTP via email — tertiary fallback
- Device binding: registered devices stored; new device requires OTP + video KYC

**Step-up authentication** (higher-value operations):
| Operation | Step-Up Required |
|---|---|
| Transfer > ₹50,000 | OTP re-verification |
| New beneficiary add | OTP + 4-hour cooling period |
| International transfer | Video call verification + manual review |
| Net banking limit increase | Branch visit or video KYC |

### Staff Authentication

- MFA mandatory for all staff (no exceptions)
- Hardware security key (FIDO2/YubiKey) for: system administrators, makers above threshold, all checkers
- Privileged Access Management (PAM): CyberArk / HashiCorp Vault for admin credentials
- Session timeout: 15 minutes idle for net banking; 5 minutes for admin panel
- Concurrent login prevention: banking staff cannot have two simultaneous sessions

### System-to-System Authentication

- Mutual TLS (mTLS) between all internal services
- Service accounts with short-lived tokens (Vault dynamic secrets)
- No static passwords in config files or environment variables

---

## Authorization: Maker-Checker Security

### The Maker-Checker Model

No single person can both initiate and approve a financial transaction:

```
Maker role:
  - Can SUBMIT transactions for approval
  - CANNOT approve their own submissions
  - CANNOT approve submissions from their direct team (anti-collusion)

Checker role:
  - Can APPROVE or REJECT submissions
  - Cannot SUBMIT
  - Dual-checker for high-value: two different checkers must approve

Super Checker:
  - Required for transactions above ₹1 crore
  - System Admin level
  - Additional logging + notification to senior management
```

### RBAC Matrix

| Role | Submit | Approve | View | Modify Config | Approve High-Value |
|---|---|---|---|---|---|
| Teller | ✓ | ✗ | Own branch | ✗ | ✗ |
| Checker | ✗ | ✓ (others' only) | All branch | ✗ | ✗ |
| Branch Manager | ✓ | ✓ (up to ₹10L) | All branch | Branch only | ✗ |
| Regional Manager | ✓ | ✓ (up to ₹1Cr) | Region | Region | ✗ |
| Super Checker | ✗ | ✓ (any amount) | All | ✗ | ✓ |
| System Admin | ✗ | ✗ | All | All | ✗ (separation of duties) |

**Separation of duties**: System Admin cannot approve transactions. Finance roles cannot modify system configuration.

### Cryptographic Approval Signatures

To prevent: checker approving without reviewing, or system manipulation after approval:

```
Approval process:
  1. Checker reviews transaction details
  2. Checker clicks Approve
  3. System generates: sign(transaction_hash, checker_private_key)
  4. Signature stored with transaction record
  
Verification:
  - Any audit can verify: signature valid AND matches exact transaction data
  - If transaction data was modified after approval: signature invalid
  - Detect tampering at the cryptographic level
```

Checker keys: issued by bank's internal CA; stored in YubiKey (never exportable).

---

## Data Protection

### Sensitive Data Classification

| Data Class | Examples | Protection |
|---|---|---|
| Tier 1 — Highest | Account numbers, CIF, NTB data | Encrypted at rest + in transit; access audited |
| Tier 2 — High | PAN card, Aadhaar (masked), CIBIL score | Encrypted; limited access roles |
| Tier 3 — Medium | Contact info, address | Encrypted; standard access |
| Tier 4 — Low | Transaction category, branch code | No special encryption required |

### Aadhaar Handling

Aadhaar is sensitive per UIDAI regulations:

- Only last 4 digits stored (masked Aadhaar: XXXX-XXXX-1234)
- Full Aadhaar never stored in bank DB (passed through for UIDAI authentication, not stored)
- Aadhaar OTP verification via UIDAI API — one-time use, not retained
- Any Aadhaar data breach = mandatory UIDAI + CERT-In reporting within 6 hours

### Encryption at Rest

| Storage | Encryption |
|---|---|
| Postgres tablespace | AES-256 (TDE — Transparent Data Encryption) |
| S3 (statements, documents) | AES-256-GCM, SSE-KMS |
| Redis | In-memory; not encrypted at rest by default — mitigate with in-app encryption for sensitive keys |
| Kafka messages | Payload encryption via application layer for sensitive events |

### Column-Level Encryption

For highest-sensitivity columns (account number, IFSC, balance):

- Application-level encryption using bank's KMS
- Encrypted before writing to Postgres
- Decrypted after reading, in application memory
- DB admin cannot read account numbers — only encrypted value visible in DB

---

## Network Security

```
Internet
    │
    ▼
[DDoS protection + WAF] — OWASP rules + banking-specific patterns
    │
    ▼
[DMZ] — Web Application Firewall, load balancer
    │
    ▼
[Application Tier] — Core banking services (private subnet)
    │
    ▼
[Data Tier] — Postgres, Redis, Kafka (most restricted subnet)
    │
    ▼
[HSM] — Hardware Security Module (isolated segment, physical hardware)
```

Network rules:
- Application tier: inbound from DMZ only; outbound to data tier + SWIFT/NPCI/external APIs
- Data tier: inbound from application tier only; no internet
- HSM: inbound from data tier only; air-gapped network
- All inbound/outbound: logged and monitored
- Intrusion Detection System (IDS): real-time alerting on suspicious patterns

---

## Audit Logging

Banking audit logging must be:

1. **Immutable**: no delete API, append-only
2. **Complete**: every action by every actor
3. **Tamper-evident**: hash chain or WORM storage
4. **Long-retained**: 7-10 years (RBI mandate)
5. **Searchable**: regulators may ask for specific records

### What's Audited

Every action in these categories:

```
Authentication:
  - Login success/failure (with IP, device, timestamp)
  - Password change
  - MFA enable/disable
  - Session timeout

Account Operations:
  - Account open/close
  - Balance inquiry (for high-value accounts: full audit)
  - Account detail change (address, nominee, contact)

Transactions:
  - Every fund transfer: amount, from, to, initiator, approver, timestamp
  - Rejection reason for declined transactions
  - Reversal details

Maker-Checker:
  - Submission (by whom, what, when)
  - Approval (by whom, at what time, with what authority)
  - Rejection (by whom, reason)

System Administration:
  - Config change (before/after value, changed by whom)
  - User creation/deletion/privilege change
  - Any access to production system
```

### Audit Log Storage

- Primary: append-only table in Postgres (fast recent queries)
- Archive: S3 with Object Lock (WORM) — set at write time; retention: 10 years
- Hash chain: each log entry contains SHA-256 hash of previous entry
- Verification tool: run weekly to verify chain integrity

---

## Secrets Management

| Secret | Storage | Rotation |
|---|---|---|
| DB passwords | HashiCorp Vault | 30 days automatic |
| API keys (SWIFT, NPCI) | Vault with HSM backing | Quarterly |
| Encryption master keys | HSM (never leaves hardware) | Annual ceremony |
| Staff private keys (signing) | YubiKey hardware token | On device replacement |
| TLS certificates | Vault PKI secrets engine | 90 days auto-renewal |
| JWT signing key | Vault | 7 days (short-lived) |

**Security ceremonies**: major key rotation requires: Security Officer present, dual-person control, documented procedure, post-rotation verification.

---

## API Security (Internal)

Banking internal APIs are not public — but must be secured:

- **mTLS**: all service-to-service calls use mutual TLS
- **JWT with short expiry**: 5-minute access tokens between services
- **API versioning**: consumer contract testing prevents breaking changes
- **Request signing**: idempotency key + timestamp + HMAC on critical banking APIs
- **IP allowlisting**: only known bank IPs can call core banking APIs (no internet exposure)

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Hardware YubiKey for all checkers | Cryptographically unforgeable approvals | Cost (~₹5,000/device), logistics |
| Column-level encryption | DB admin can't see sensitive data | Performance overhead, complex queries |
| WORM audit log storage | Tamper-proof, regulatory | Cannot correct mistakes (need compensating entries) |
| Maker-checker for all significant transactions | Eliminates single-person fraud | Slows operations; operational overhead |
| Short JWT expiry (5 min) | Rapid revocation | Frequent token refresh, more Vault calls |

---

## Interview Discussion Points

- **"How do you prevent insider fraud?"** → Separation of duties (makers can't check); cryptographic approval signatures (can't tamper post-approval); all actions audited with who-did-what; anomaly detection on unusual approval patterns
- **"How do you secure Aadhaar data?"** → Never store full Aadhaar; pass-through to UIDAI for authentication; store only masked last 4 digits; breach must be reported to UIDAI within 6 hours
- **"What's the maker-checker limit structure?"** → Tiered: teller < ₹1L; branch manager < ₹10L; regional manager < ₹1Cr; super checker > ₹1Cr. Dual checker (two approvers) above certain thresholds.
- **"How do you prove a checker actually approved something and didn't have it forged?"** → Cryptographic signature: checker signs transaction hash with their private key stored on YubiKey. Signature verifiable with public key. Tampered transaction = invalid signature.
- **"How do you meet RBI audit requirements?"** → Append-only audit log in WORM storage (S3 Object Lock), 10-year retention, hash chain tamper detection, dedicated regulatory API for audit data export
