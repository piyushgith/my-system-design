# 08 — Security Design: Notification System

---

## Objective

Define the security architecture for the Notification System covering authentication, authorization, PII handling, secrets management, audit logging, and compliance controls. The system handles sensitive user contact information and has potential for significant abuse.

---

## Threat Model

### Threat Actors

| Actor | Capability | Risk |
|-------|-----------|------|
| External attacker | No credentials | API abuse, spam via unprotected endpoint |
| Malicious internal service | Valid service JWT | Mass spam, PII exfiltration |
| Compromised producer service | Stolen service credentials | Unauthorized notification sends |
| Insider threat | DB access | PII exposure from notification logs |
| User | Valid user session | Preference manipulation, inbox access |

### Key Assets to Protect

| Asset | Sensitivity | Risk if Compromised |
|-------|-------------|-------------------|
| Email addresses + phone numbers | PII (GDPR) | Spam, identity theft |
| OTP values in notification content | High | Account takeover |
| Provider API keys (SendGrid, Twilio) | Critical | Mass spam, financial abuse |
| User notification tokens (push) | Medium | Push spam |
| User unsubscribe tokens | Medium | Unauthorized unsubscribes |

---

## Authentication

### Service-to-Service (Producer → Notification API)

```
Method: JWT signed with RS256 (asymmetric)
Issuer: Internal Identity Service
Claims: sub (service-id), iat, exp (15 min), scope (notification:write)
Transport: Authorization: Bearer <token> over mTLS
```

- JWTs are short-lived (15 minutes) and cached by producer services
- The Notification API validates: signature, expiry, issuer, and `scope` claim
- mTLS enforced at the service mesh layer (Istio) as an additional layer
- No producer uses a long-lived API key — only JWTs

### User-Facing APIs (Preference + Inbox)

```
Method: JWT issued by platform Identity Service
Claims: sub (user-id), roles, exp
Transport: HTTPS only
```

- Preference API: users can only access their own preferences (`sub` must match `user_id` in path)
- Inbox API: same ownership enforcement
- Admin operators use a separate admin JWT with elevated scope

### Unsubscribe Endpoint

```
Method: Signed token (HMAC-SHA256)
Token: base64(user_id + channel + category + exp) + HMAC signature
No auth header required — link appears in email
```

- Token is single-use (stored hashed in DB, nullified after use)
- Token expires in 30 days
- Token cannot be guessed: HMAC prevents forgery

---

## Authorization (RBAC)

### Roles

| Role | Can Do | Cannot Do |
|------|--------|----------|
| `notification:write` | Submit individual notifications | Submit batch campaigns |
| `notification:batch` | Submit batch campaigns | View other services' notifications |
| `notification:admin` | All operations, cancel any notification | |
| `template:manage` | CRUD templates | Submit notifications |
| `preference:read` | Read user preferences (ops use only) | Modify preferences |
| `user` | Manage own preferences, read own inbox | Access other users' data |

### Enforcement

- Role claims are embedded in the JWT
- Notification API validates `scope` claim against the endpoint's required permission
- Row-level enforcement: producers cannot query or cancel notifications from other producers
  - `producer_service` column enforced by WHERE clause on all producer-facing queries
  - Not enforced in application code alone — view-level + application-level dual enforcement

---

## PII Handling

### What Is Stored and Where

| PII Type | Stored In | How Protected |
|----------|---------|--------------|
| Email address | Delivery attempts table (temporarily) | Encrypted at rest, purged after 90 days |
| Phone number | Delivery attempts table (temporarily) | Same |
| OTP/codes in notification variables | notification_requests.variables JSONB | Encrypted at column level |
| Device push tokens | Resolved at dispatch time from Device Registry | Not stored in Notification System |
| User name in template variables | notification_requests.variables JSONB | Encrypted at column level |

### PII Minimization Strategy

**Goal:** The Notification System should NOT be a PII store. It is a delivery system.

- Email addresses and phone numbers are resolved from User Profile Service at dispatch time by the Fanout Service
- Email + phone are written to `delivery_attempts` only temporarily (for provider webhook correlation)
- The `delivery_attempts.recipient_address` column is purged after 90 days
- `notification_requests.variables` JSONB column uses PostgreSQL column-level encryption (pgcrypto) for values
- Logs never contain raw PII — email addresses are masked as `al***@ex***.com` in all log statements

### GDPR Right-to-Erasure

When a user requests account deletion:
1. Preference Service: delete all preference rows for `user_id`
2. Notification System: update `notification_requests` to null the `variables` JSONB for that user
3. In-App Inbox: delete all inbox items for that user
4. Delivery attempts: the 90-day partition drop handles historical data
5. ClickHouse: user_id is replaced with a pseudonymous hash for analytics continuity

---

## Secrets Management

### Provider API Keys

- Never stored in code, environment variables, or configuration files
- Stored in **HashiCorp Vault** (or AWS Secrets Manager)
- Dispatcher services request keys at startup via Vault's AppRole authentication
- Keys are rotated every 90 days; dispatchers re-fetch on rotation event via Vault Agent
- Each dispatcher instance uses its own API key (pool of keys per provider)

### JWT Signing Keys

- RS256 key pairs managed in Vault
- Public key endpoint published for consumers at `/.well-known/jwks.json`
- Key rotation: new key pair generated quarterly; 30-day overlap for in-flight JWTs

### Database Credentials

- PostgreSQL credentials: Vault dynamic secrets (credentials valid for 1 hour, auto-rotated)
- No static DB passwords in configuration files

---

## Encryption

| Layer | Approach |
|-------|---------|
| In transit | TLS 1.3 minimum on all external traffic; mTLS on internal service mesh |
| At rest (PostgreSQL) | Transparent Data Encryption (TDE) at the disk level |
| At rest (Redis) | Redis 7 encryption at rest (or disk-level encryption) |
| At rest (Kafka) | Kafka message-level encryption for notification payload topics |
| Column-level | `notification_requests.variables` encrypted with pgcrypto (AES-256) |
| S3 / archive | SSE-S3 (AES-256) for archived partitions |

---

## Rate Limiting and Abuse Prevention

### API Layer Rate Limiting

| Scenario | Limit | Response |
|---------|-------|---------|
| Per producer service | 10,000 req/min | 429 + Retry-After |
| Per producer (batch campaigns) | 10/min | 429 |
| Unsubscribe endpoint | 5/IP/min | 429 (prevents token brute-force) |
| Preference PATCH | 10/user/min | 429 |

### Abuse Detection

| Pattern | Detection | Response |
|---------|-----------|---------|
| Single producer sends to > 1M users/hour without batch route | Anomaly alert | Throttle + alert ops |
| Unusual volume for a new producer service | Baseline deviation alert | Require explicit capacity approval |
| SMS to numbers that consistently bounce | Bounce rate threshold | Auto-disable template for that number range |
| High DLQ rate for specific producer | Alert + circuit breaker | Block producer notifications temporarily |

---

## Audit Logging

### What Is Audited

| Event | Logged Fields |
|-------|-------------|
| Notification submitted | notification_id, producer_service, recipient_user_id, category, priority, timestamp, source_ip |
| Notification cancelled | notification_id, cancelled_by, timestamp |
| Preference updated | user_id, channel, category, old_value, new_value, changed_by, timestamp |
| Template created/updated | template_id, version, changed_by, timestamp |
| Hard unsubscribe | user_id, channel, reason, timestamp |
| Provider API key accessed | key_id, service, timestamp (Vault audit log) |
| Bulk campaign launched | batch_id, operator, segment_id, estimated_recipients, timestamp |

### Log Storage
- Audit logs written to a separate append-only PostgreSQL table + replicated to S3
- S3 bucket: write-once, read-many (Object Lock enabled for compliance)
- Retention: 7 years (financial/compliance standard)
- Access: restricted to security team and compliance auditors only

### Log Masking Rules
- All log statements pass through a masking filter before writing
- Email: `alice@example.com` → `al***@ex***.com`
- Phone: `+919876543210` → `+91987******10`
- OTP values: always `[REDACTED]`
- Variables JSONB in logs: keys preserved, values masked if key name suggests PII

---

## API Gateway Security Controls

| Control | Implementation |
|---------|--------------|
| TLS termination | ALB / Kong with TLS 1.3 only |
| OWASP Top 10 protection | WAF rules (AWS WAF or Kong plugins) |
| SQL injection | All queries use parameterized statements (JPA/JDBC) |
| Request size limit | Max 50 KB per notification submission |
| Content-Type enforcement | Only `application/json` accepted |
| CORS policy | Only internal domains allowed (Notification API is not public) |
| IP allowlist | Notification submission API accessible only from internal VPC |

---

## SMS-Specific Compliance

| Regulation | Requirement | Implementation |
|-----------|-------------|---------------|
| India DND Registry | Must not send marketing SMS to DND-registered numbers | DND check before SMS dispatch; nightly DND registry sync |
| TCPA (US) | Prior express consent required for marketing SMS | Consent stored in Preference Service, verified before dispatch |
| CAN-SPAM | One-click unsubscribe in all marketing emails | `List-Unsubscribe` header + unsubscribe endpoint |
| GDPR | Right to erasure, data minimization | PII purge on account deletion, column-level encryption |

---

## Interview Discussion Points

- Why use asymmetric JWT (RS256) instead of symmetric HMAC (HS256) for service-to-service auth in a microservices setup?
- What is the risk of storing OTP values in `notification_requests.variables`, and how does column-level encryption mitigate it?
- How do you prevent a compromised producer service (e.g., order-service) from sending spam to all 100M users?
- Why are push device tokens not stored in the Notification System, and what service boundary does this enforce?
- What happens to audit logs if the audit DB is full or unavailable — does the notification fail?
- How does mTLS differ from JWT in terms of what it protects against?
