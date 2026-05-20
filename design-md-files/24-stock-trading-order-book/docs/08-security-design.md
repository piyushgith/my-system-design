# 08 — Security Design: Stock Trading Order Book

## Objective

Define authentication, authorization, data protection, and regulatory compliance controls for a financial exchange platform. Financial systems face targeted attacks; security must be defense-in-depth.

---

## Threat Model

| Threat | Risk | Control |
|--------|------|---------|
| Unauthorized order submission | Trade on someone else's account | Mutual TLS + JWT per participant |
| Order replay attack | Re-submit captured order request | `clientOrderId` idempotency + timestamp window |
| Market manipulation via wash trades | Artificial price movement | Self-trade prevention + surveillance |
| Risk engine bypass | Submit orders exceeding limits | Risk check is mandatory gate, cannot be bypassed by gateway |
| Data exfiltration of order book | Front-running by insider | Rate limiting on Level 2 queries, access logging |
| DDoS on order gateway | Trading halt | Rate limiting, WAF, connection limits per IP |
| Malicious matching engine code | Incorrect fills | Code signing, immutable deployment, audit of every match |
| Admin credential theft | System-wide access | MFA, break-glass procedures, privileged access management |

---

## Authentication

### Participant (Client) Authentication

**API clients (REST):**
- OAuth 2.0 Client Credentials flow for service-to-service (automated trading systems)
- JWT Bearer tokens (15-minute expiry) for human-operated clients
- API keys for market data feed subscribers (read-only, no order access)

**Institutional / FIX clients:**
- Mutual TLS (mTLS) — client presents a certificate issued by the exchange CA
- FIX session-level logon with `SenderCompID` + `TargetCompID` + encrypted password in tag 554
- Session is bound to a specific network IP range (whitelist)

**No username/password for trading APIs** — only certificate or machine credentials. This eliminates phishing as an attack vector for high-value trading access.

### Internal Service Authentication

- All internal services communicate via mTLS using certificates from an internal CA (Vault PKI)
- Service mesh (Istio) enforces mTLS transparently — no application code needed
- Service accounts per deployment — no shared credentials

---

## Authorization

### RBAC Model

| Role | Permissions |
|------|------------|
| RETAIL_TRADER | Submit/cancel own orders, view own orders, view market data |
| INSTITUTIONAL_TRADER | Submit/cancel own orders, higher rate limits, FIX access |
| MARKET_MAKER | Submit/cancel orders, tighter spread obligations, reduced fees |
| RISK_MANAGER | Read all participant positions, adjust risk limits (no order access) |
| COMPLIANCE_OFFICER | Read-only access to all order events and trades, audit log access |
| EXCHANGE_ADMIN | Halt/resume trading, add/suspend instruments, manage participants |
| SYSTEM_SERVICE | Internal service-to-service (machine role only) |

### Participant Isolation

Every order request is validated against the JWT's `participantId` claim:
- Cannot submit orders on behalf of another participant
- Cannot view another participant's orders
- Cannot modify another participant's positions

This check happens in the Order Gateway — never trusted from client input.

---

## Order-Level Controls

### Self-Trade Prevention

Exchange must prevent wash trades (a participant buying their own sell order). Matching engine checks:
- If buy order's `participantId` == resting sell order's `participantId` → skip that resting order (or cancel the aggressor, configurable)
- Log self-trade attempt for surveillance

### Duplicate Order Detection

`clientOrderId` uniqueness enforced within participant scope:
- Redis key `idem:{participantId}:{clientOrderId}` with 24-hour TTL
- Duplicate submission returns original response (idempotent)
- Protects against client retry storms causing double-orders

### Timestamp Validation

Orders with `submittedAt` timestamp more than 5 seconds in the past rejected:
- Prevents replay of captured order packets
- Server-side clock used (NTP-synchronized, PTP where available)

### Anti-Front-Running

- Market data delayed by configurable amount for non-co-located clients
- Level 2 (full depth) queries rate-limited — prevents using API as high-frequency data feed
- Co-location access logged and audited

---

## Data Protection

### Encryption at Rest

- PostgreSQL: TDE (Transparent Data Encryption) at storage level
- S3 archival: SSE-KMS with customer-managed keys
- Redis: encrypted at rest (Redis Enterprise) for participant data

### Encryption in Transit

- All external APIs: TLS 1.3 minimum
- Internal services: mTLS
- FIX sessions: TLS 1.2 minimum (older FIX clients may not support 1.3)
- Kafka: TLS for broker communication + ACL-based topic authorization

### PII Handling

- Participant personal data stored separately from trading data
- Trading records reference `participantId` (opaque UUID) — not name or account number
- PII database access requires separate authorization + audit log
- GDPR right-to-erasure: participant personal data can be pseudonymized; trading records retained for regulatory requirement (7 years, cannot be deleted)

### Secrets Management

- HashiCorp Vault for all secrets (DB credentials, API keys, signing keys)
- Dynamic secrets: Vault generates short-lived PostgreSQL credentials per service
- Key rotation: Vault handles rotation transparently
- No secrets in environment variables, config files, or Kubernetes manifests (Vault Agent sidecar injects secrets)

---

## Network Security

### Zone Segmentation

```
Internet
    │
    ▼
WAF + DDoS Protection (CloudFlare / AWS Shield)
    │
    ▼
DMZ (Public Subnet)
    ├── Order Gateway (HTTPS only)
    ├── Market Data WebSocket (WSS only)
    └── FIX Gateway (mTLS, whitelisted IPs only)
    │
    ▼
Application Zone (Private Subnet)
    ├── Matching Engine Pods (no external access)
    ├── Risk Engine (no external access)
    └── Kafka Brokers
    │
    ▼
Data Zone (Private Subnet, isolated)
    ├── PostgreSQL (application zone only)
    └── Redis (application zone only)
```

Matching engine pods have **no inbound internet access**. The only way to submit an order is through the Order Gateway.

### Rate Limiting

Applied at WAF layer (before hitting application):
- Per IP: 1,000 req/sec for market data, 100 req/sec for order submission
- Per participant JWT: as defined in API design
- Burst allowance: 2x limit for 5 seconds (handles legitimate bursts)
- Rejected requests: 429 with `Retry-After` header

---

## Audit Logging

Every security-relevant event logged with:
- `traceId` (correlation across services)
- `participantId` or `serviceId`
- Action performed
- Resource accessed
- IP address and user agent
- Outcome (success/failure)
- Timestamp (nanosecond precision)

Events logged to append-only audit trail (separate from operational DB):
- Order submission (success and rejection)
- Authentication events (login, logout, token refresh)
- Authorization failures
- Admin actions (halt trading, suspend participant)
- Data access (who queried what historical data)

**Tamper evidence:** HMAC chain — each audit record includes hash of previous record. External auditor can verify chain integrity without access to private key.

---

## Regulatory Compliance Controls

### FINRA / SEC Requirements (US Equities)

| Requirement | Control |
|-------------|---------|
| Order audit trail (OATS/CAT) | Nanosecond-timestamped event journal |
| Best execution | Price-time priority matching, documented |
| Market access rule (Rule 15c3-5) | Pre-trade risk checks mandatory |
| 17a-4 record retention | 7-year immutable archival |
| Market manipulation detection | Surveillance system on trade data |

### Market Surveillance

Downstream consumer of `trade-executed` events runs surveillance rules:
- Wash trade detection (self-trades)
- Layering detection (many orders cancelled before fill)
- Spoofing detection (large orders placed then quickly cancelled)
- Unusual trading pattern alerts → compliance officer review queue

---

## Spring Security Configuration (if REST layer uses Spring Boot)

- `@PreAuthorize("hasRole('TRADER') and #orderId == authentication.principal.participantId")` for order cancel
- JWT validation filter: validates signature, expiry, issuer
- CSRF: disabled for stateless REST API (using JWT Bearer, not cookies)
- CORS: strict allow-list of known client origins
- Security headers: `Strict-Transport-Security`, `X-Content-Type-Options`, `X-Frame-Options`

---

## Incident Response

| Scenario | Response |
|----------|---------|
| Suspected compromised participant credentials | Immediate token revocation, account suspension, forensic log review |
| Unusual order pattern (potential manipulation) | Alert compliance team, halt participant if confirmed |
| Matching engine crash | Automatic circuit breaker halt, pod restart, Slack/PagerDuty alert |
| Redis unavailable | Fail-closed: reject all new orders, trading effectively halted |
| Data breach detected | Incident commander declared, regulatory notification within 72 hours (GDPR) |
| Trading halt trigger | Automated halt, human review before resume |
