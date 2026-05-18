# 08 — Security Design: Payment Gateway / Wallet System

---

## Objective

Define security architecture for a payment platform handling financial transactions — covering PCI DSS compliance, data encryption, fraud prevention, API security, key management, and audit requirements.

---

## Threat Model

| Threat | Impact | Mitigation |
|---|---|---|
| Card data theft | PCI DSS violation, fines, reputation | Tokenization, never store raw PAN |
| Man-in-the-middle | Payment interception | TLS 1.3, certificate pinning |
| Double spend | Financial loss | Idempotency + DB constraints |
| Account takeover | Fraudulent transfers | MFA, anomaly detection, session limits |
| API key compromise | Unauthorized charges | API key rotation, IP allowlisting |
| Insider threat | Unauthorized fund transfer | RBAC, maker-checker, audit logs |
| Replay attacks | Duplicate charges | Idempotency keys, request signing |
| SQL injection | Data breach | Parameterized queries only |
| Webhook forgery | Fake payment confirmations | Webhook signature verification |

---

## PCI DSS Compliance

Payment card data is the most regulated data in finance.

### PCI DSS Scope Tiers

| SAQ Level | Scope | Who |
|---|---|---|
| SAQ A | No card data on premises (iframe/redirect only) | Merchants using our gateway |
| SAQ D | Full compliance (card data handled) | Our payment gateway itself |

**We (the payment gateway) are full SAQ D.** This means:

- Annual penetration testing
- Quarterly vulnerability scans
- Strict network segmentation (Cardholder Data Environment — CDE)
- No card data in logs
- Strict access control to production systems (need-to-know)
- File integrity monitoring on all CDE systems

### Cardholder Data Environment (CDE)

```
External Network
      ↓
   DMZ (API gateway, WAF, load balancer)
      ↓
  Payment API servers (CDE boundary starts here)
      ↓
  Payment DB (Postgres — card tokens, transaction data)
      ↓
  HSM (Hardware Security Module — encryption keys)
```

CDE systems: isolated network segment. No developer access without MFA + jump box + audit log. No direct internet access from CDE.

---

## Card Data Handling

### Tokenization

Raw card numbers (PAN) never stored in our DB:

```
User submits card → JavaScript SDK (iframe) → Tokenization vault (Stripe/Spreedly/own vault)
                                             → Returns token: tok_visa_4242
                                             
Our system stores: { token, last4, brand, exp_month, exp_year }
```

For payments: submit token to card network. Token maps to real card inside vault.

**Own vault (at scale)**:
- Format-preserving encryption (FPE): token looks like a card number but is encrypted
- Vault keys stored in HSM
- Token → PAN mapping: HSM decrypts, submits to Visa/Mastercard

### CVV

- Never stored (even encrypted). PCI DSS explicitly prohibits post-authorization CVV storage.
- Used only for card-present and initial authorization.

---

## Encryption

### Data at Rest

| Data | Encryption |
|---|---|
| Card tokens | AES-256-GCM, key in HSM |
| Bank account numbers | AES-256-GCM, key in AWS KMS |
| User PII (name, address) | AES-256-GCM, application-level |
| Transaction records | Postgres encryption at rest (AWS RDS) |
| Private keys (API keys) | bcrypt hash; never stored plaintext |

### Data in Transit

- TLS 1.3 for all external connections
- TLS 1.2 minimum for internal service-to-service (mTLS in service mesh)
- Certificate rotation: automated via AWS Certificate Manager
- HSTS: `Strict-Transport-Security: max-age=31536000; includeSubDomains; preload`

### Key Management

- AWS KMS for application-level encryption keys
- HSM (AWS CloudHSM) for card tokenization master keys
- Key hierarchy: Master Key (HSM) → Data Encryption Keys (KMS) → Encrypted data
- Envelope encryption: data encrypted with DEK; DEK encrypted with master key
- Key rotation: DEKs rotated quarterly; master key annually
- Key material never leaves HSM in plaintext

---

## API Security

### Merchant API Keys

- Generated: cryptographically random 256-bit secrets
- Stored: bcrypt hash of key (not plaintext) in DB
- Format: `pk_live_` prefix (publishable) vs `sk_live_` prefix (secret)
- Publishable key: safe for frontend use (limited permissions)
- Secret key: server-side only (full permissions)

### Request Signing (HMAC)

For webhooks and high-security API calls:

```
Signature = HMAC-SHA256(timestamp + "." + request_body, api_secret)
Header: X-Signature: v1=<signature>
Header: X-Timestamp: <unix_timestamp>
```

Receiver verifies:
1. Compute expected signature from body + timestamp
2. Compare with header (timing-safe comparison)
3. Reject if timestamp > 5 minutes old (replay prevention)

### Webhook Security

Webhooks sent to merchant servers must be verified:

- Sign webhook payload with merchant's secret key
- Merchant verifies signature before processing
- Webhook endpoint must be HTTPS
- Reject duplicate webhook delivery (deduplication by webhook_id)

### OAuth2 for Platform Integrations

Merchants authenticate to our portal via OAuth2:
- Authorization code flow
- PKCE for mobile/SPA clients
- Scopes: `payments:read`, `payments:write`, `refunds:write`
- Token rotation on use

---

## Authentication & Authorization

### User Authentication (wallet users)

- JWT access tokens: 15-minute expiry
- Refresh tokens: 7-day expiry, stored HttpOnly cookie
- MFA mandatory for: transfers > ₹10,000, new bank account linking, password change
- OTP via TOTP app preferred; SMS OTP fallback (SIM swap risk acknowledged)
- Device fingerprinting: new device triggers step-up authentication

### Merchant Authentication

- API key authentication (HMAC-signed requests)
- IP allowlisting: merchants register IP ranges; requests from other IPs rejected
- MFA mandatory for dashboard access
- Role-based access within merchant account: Admin, Developer, Analyst

### Internal Admin Authentication

- SSO via Okta (SAML 2.0)
- Hardware security key (FIDO2) mandatory for production access
- Just-in-time (JIT) access: production access provisioned for specific time window
- All actions logged (who, what, when)

---

## Fraud Detection

### Transaction Risk Scoring

Every payment scored 0-100:

**Input signals**:
- User history: account age, past fraud, chargeback rate
- Transaction: amount vs historical average, currency, merchant category
- Device: new device, VPN/proxy detected, location anomaly
- Velocity: payments in last 1h, 24h, 7d
- Network: shared device across accounts, linked card fraud history

**Score mapping**:
| Score | Action |
|---|---|
| 0-30 | Auto-approve |
| 30-60 | Additional verification (3DS, OTP) |
| 60-80 | Manual review queue |
| 80-100 | Auto-decline + flag account |

### Chargeback Prevention

- 3DS2 for all card-not-present transactions → liability shifts to issuer
- Velocity limits per card number (stored as hash, not plaintext)
- Restrict: same card → multiple accounts
- Soft descriptor (merchant name on card statement): must be recognizable to reduce friendly fraud

---

## Audit Logging

Every financial operation logged with:

| Field | Description |
|---|---|
| eventId | Unique event ID |
| timestamp | Millisecond precision |
| actorId | User/merchant/system performing action |
| actorIp | IP address |
| action | PAYMENT_INITIATED, DEBIT_APPLIED, CREDIT_APPLIED, etc. |
| resourceId | paymentId, walletId, accountId |
| amount | Amount affected |
| currency | Currency code |
| result | SUCCESS / FAILURE + reason code |
| previousState | Balance/status before |
| newState | Balance/status after |

**Immutability**: audit logs written to append-only store (S3 + Glacier). No delete API. Retention: 7 years (RBI mandate).

**Tamper evidence**: audit log entries hashed + chained (each entry includes hash of previous entry). Tampering detectable by hash chain verification.

---

## Secrets Management

- No secrets in code, environment variables (unencrypted), or config files
- AWS Secrets Manager: DB credentials, API keys, encryption key IDs
- Automatic rotation: DB passwords rotated every 30 days via Secrets Manager + RDS integration
- Applications fetch secrets on startup; refresh without restart via SDK
- Secret access: IAM role-based (pods have IAM role, not static credentials)

---

## Network Security

```
Internet
   ↓
WAF (AWS WAF — OWASP rule groups, rate limiting, bot mitigation)
   ↓
ALB (TLS termination)
   ↓
Payment API pods (private subnet)
   ↓
PgBouncer → Postgres (data subnet — most restricted)
          → Redis (data subnet)
          → HSM (dedicated hardware, air-gapped network)
```

Security groups:
- API pods: inbound from ALB only; outbound to DB subnet + KMS endpoint + external payment gateways
- DB subnet: inbound from API pods only; no internet access
- HSM: inbound from DB subnet only; physical isolation

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Tokenization via external vault | PCI scope reduced | Vault vendor dependency |
| HMAC request signing | Forgery protection | Client implementation complexity |
| HSM for master keys | Highest security | Cost (~$1,000/month for CloudHSM) |
| IP allowlisting for merchants | Strong access control | Ops burden on merchant side |
| Audit log hash chaining | Tamper evidence | Log verification complexity |

---

## Interview Discussion Points

- **"How do you store card data securely?"** → We don't; tokenization ensures raw PAN never reaches our systems; token maps to PAN only inside HSM
- **"How do you prevent replay attacks on payments?"** → Idempotency key (client-generated) + timestamp + HMAC signature; reject if timestamp > 5 minutes
- **"What's your PCI DSS compliance approach?"** → SAQ D for gateway; reduce merchant scope to SAQ A via iframe tokenization; CDE network isolation; annual pentest
- **"How do you audit admin actions?"** → All actions logged with actor, resource, before/after state; immutable S3 storage; hash chaining for tamper evidence
- **"How do you handle a compromised API key?"** → Immediate revocation (DB flag); replay of recent transactions to identify fraudulent payments; force merchant to issue new key; incident report
