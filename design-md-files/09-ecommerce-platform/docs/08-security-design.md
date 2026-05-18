# 08 — Security Design: E-Commerce Platform

---

## Objective

Define authentication, authorization, data protection, fraud prevention, payment security, and compliance strategy for the e-commerce platform handling financial transactions and sensitive user data.

---

## Threat Model

| Threat | Impact | Mitigation |
|---|---|---|
| Account takeover | Order fraud, data breach | MFA, anomaly detection, session limits |
| Payment fraud | Financial loss | 3DS, fraud scoring, velocity checks |
| Inventory manipulation | Oversell / undersell attacks | Rate limiting, Redis atomic ops |
| Price manipulation | Revenue loss | Server-side price validation |
| SQL injection | Data breach | Parameterized queries, JPA |
| XSS | Session hijack | CSP headers, input sanitization |
| CSRF | Unauthorized orders | CSRF tokens, SameSite cookies |
| API scraping | Competitor intelligence | Rate limiting, bot detection |
| Insider threat | Data exfiltration | RBAC, audit logs, least privilege |

---

## Authentication

### Customer Authentication

- JWT access tokens (15-min expiry) + refresh tokens (30-day expiry)
- Refresh tokens stored in HttpOnly, Secure, SameSite=Strict cookies
- Access tokens in memory only (not localStorage — XSS vulnerable)
- Token rotation on refresh (old refresh token invalidated)

### Seller / Admin Authentication

- Mandatory MFA (TOTP via Google Authenticator or SMS OTP)
- Session-based auth for admin panel (not JWT — easier to revoke)
- IP allowlisting for seller admin APIs in sensitive operations
- Hardware security key (FIDO2) for finance/payout approvals

### OAuth2 / Social Login

- Google, Facebook, Apple Sign-In via OAuth2 authorization code flow
- Store only OAuth2 provider ID + email, never password for social users
- Link multiple providers to single account

---

## Authorization

### Customer RBAC

| Role | Permissions |
|---|---|
| Guest | Browse, search, view products |
| Customer | All guest + cart, checkout, order history |
| VIP Customer | All customer + early flash sale access |
| Seller | Manage own products, inventory, orders |
| Seller Admin | All seller + payout, analytics |
| Platform Admin | All + user management, config |
| Finance | Payout approval, refund approval |

### Resource-Level Authorization

- Customers can only access their own orders
- Sellers can only modify their own product listings
- Enforce at service layer, not just UI
- Spring Security method-level `@PreAuthorize("@orderSecurity.isOwner(#orderId, authentication)")`

---

## Payment Security

### PCI DSS Compliance

E-commerce platforms handling card data must be PCI DSS compliant.

**Scope reduction strategy:**
- Never store raw card numbers on platform
- Use payment vault provider (Stripe, Braintree, Adyen)
- Platform stores only: card last4, expiry month/year, brand, payment method token
- All card capture via tokenized iframe/SDK (card data never touches our servers)
- PCI DSS scope reduced to SAQ A (minimal)

### 3D Secure (3DS2)

- Mandate 3DS for high-risk transactions (new card, large amount, new shipping address)
- 3DS exemptions for low-risk, established customers (friction reduction)
- Liability shift: if 3DS used and fraud occurs, issuer bears liability

### Payment Idempotency

- Every payment initiation carries idempotency key
- Key = `userId:orderId:attempt`
- If payment service receives duplicate request → return existing result
- Prevents double-charge on network retry

---

## Data Protection

### Sensitive Data Encryption

| Data | Storage Approach |
|---|---|
| Passwords | bcrypt (cost factor 12) — never plaintext |
| Payment tokens | Encrypted at rest (AES-256) |
| PII (address, phone) | Encrypted columns in Postgres |
| Card data | Never stored — tokenized via vault |
| Tax IDs / SSN | Encrypted + access audited |

### Database Encryption

- Postgres encryption at rest via AWS RDS encryption (AES-256)
- Column-level encryption for PII using application-level encrypt/decrypt
- Encryption keys in AWS KMS — never in code or config files
- Key rotation without downtime via envelope encryption

### Data Masking

- Logs must never contain: card numbers, CVV, passwords, full SSN
- Application log masking via custom Log4j/Logback filter
- API responses mask: card number shown as `****-****-****-4242`

---

## API Security

### Input Validation

- Validate all inputs at controller layer (Bean Validation)
- Reject payloads exceeding size limits (prevent DoS via large JSON)
- Strict content-type enforcement (`application/json` only)
- Regex validation on product IDs, order IDs to prevent injection

### SQL Injection Prevention

- JPA/Hibernate parameterized queries — no string concatenation in SQL
- No raw SQL strings in application code
- Read-only DB user for read replicas (prevents write via compromised read path)

### Rate Limiting & Bot Detection

- API Gateway rate limits per IP and per user
- Bot detection: unusual velocity, headless browser signatures, IP reputation
- CAPTCHA on: registration, checkout, password reset
- Honeypot fields on forms (bot fills them, human doesn't)

### Security Headers

```
Content-Security-Policy: default-src 'self'; script-src 'self' cdn.example.com
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
Strict-Transport-Security: max-age=31536000; includeSubDomains
Referrer-Policy: no-referrer-when-downgrade
```

---

## Fraud Detection

### Signal Collection

| Signal | High Risk Indicator |
|---|---|
| IP geolocation | Payment country ≠ billing country |
| Device fingerprint | New device on high-value order |
| Velocity | 3+ orders in 5 minutes |
| Email age | Account < 1 hour old placing large order |
| Shipping address | Multiple orders to same address, different cards |
| Card BIN | Prepaid card on high-value order |

### Fraud Scoring

- Score 0-100 assigned per transaction
- Score < 30: auto-approve
- Score 30-70: 3DS required
- Score 70-85: manual review queue
- Score > 85: auto-reject + account flag

### Implementation Options

- Rule-based engine (startup): fast, transparent, maintainable
- ML scoring model (growth): better accuracy, harder to explain
- Third-party fraud platform (Sift, Kount, Stripe Radar): best accuracy, cost per transaction

---

## Secrets Management

- No secrets in code, config files, or environment variables in plain text
- AWS Secrets Manager / HashiCorp Vault for all credentials
- Spring Boot integration via `spring-cloud-aws-secrets-manager`
- Secrets rotated on schedule; app fetches fresh on startup
- DB passwords rotated via RDS rotation + Secrets Manager integration

---

## Audit Logging

Every security-sensitive action logged with:
- WHO (userId, IP, device)
- WHAT (action performed)
- WHEN (timestamp, milliseconds)
- WHERE (resource ID affected)
- RESULT (success/failure)

Sensitive actions requiring audit log:
- Login success/failure
- Password change
- Address add/modify
- Order placement
- Refund issued
- Admin accessing another user's account

Audit logs stored in append-only store (S3 + CloudTrail) — not modifiable by application.

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| JWT for customer auth | Stateless, scalable | Can't revoke immediately; mitigate with short expiry |
| Session for admin | Instant revoke | Sticky sessions or shared session store needed |
| Third-party payment vault | PCI scope reduced | Vendor lock-in, cost per transaction |
| Column-level encryption | Granular protection | Query on encrypted field is slower |
| 3DS mandate | Liability shift | Increased checkout friction, cart abandonment |

---

## Alternatives Considered

| Alternative | Rejected Because |
|---|---|
| localStorage for JWTs | XSS vulnerable |
| Platform stores card numbers | PCI DSS full scope, enormous compliance burden |
| Rule-only fraud detection | High false negative rate at scale |
| Shared admin/customer auth | Blast radius too high on admin compromise |

---

## Interview Discussion Points

- **"How do you prevent double charging?"** → Idempotency key on payment service, deduplication table
- **"How do you store payment info securely?"** → Tokenization via vault, never raw card on platform
- **"What PCI DSS scope applies?"** → SAQ A if iframe/SDK tokenization used correctly
- **"How do you handle fraud?"** → Risk scoring + 3DS friction + ML signals; explain tradeoff between fraud loss vs cart abandonment
- **"How do you audit admin actions?"** → Append-only audit log, never deletable by app layer
