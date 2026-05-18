# 09 — Caching Strategy: Banking Core System

---

## Objective

Define a conservative, compliance-aware caching strategy for a banking system — where incorrect data display can cause customer disputes, regulatory issues, and where the "aggressive caching" approach of consumer web is often inappropriate.

---

## Banking Caching Philosophy

Banking caching is more restrictive than standard web applications:

| Principle | Implication |
|---|---|
| Regulatory accuracy | Some data must always be current (cannot cache) |
| Audit trail | What data was served when must be traceable |
| Customer trust | Showing wrong balance → customer complaint → regulatory escalation |
| Compliance reports | Must reflect point-in-time data, not cached values |
| No stale debit | Any cache-read used for fund authorization must be authoritative |

**Rule**: Cache for performance, never cache for authorization decisions.

---

## What Can and Cannot Be Cached

| Data | Cache? | TTL | Reason |
|---|---|---|---|
| Account balance (display) | Yes — Redis | 10-30s | Acceptable for home screen; refresh on transaction |
| Account balance (for debit auth) | No | — | Must read from Postgres primary |
| Transaction history (recent 30 days) | Yes — Redis | 60s | Acceptable staleness for display |
| Transaction detail (individual) | No | — | Must be authoritative for disputes |
| Account details (name, type, status) | Yes — Redis | 300s | Low mutation, display only |
| Pending maker-checker requests | No | — | Must be real-time for checkers |
| Interest rates (product rates) | Yes — Redis | 3600s | Changes very infrequently |
| Branch/IFSC master data | Yes — Redis | 86400s | Essentially static |
| Exchange rates (FX) | Yes — Redis | 60s | Changes per market |
| Compliance flags (KYC, AML freeze) | No | — | Must be current; delay = regulatory violation |
| User session | Yes — Redis | 900s | 15-min idle timeout |

---

## Layer 1: Balance Cache (Display Only)

```
GET /accounts/{accountId}/balance
  1. Check Redis: GET balance_display:{accountId}
  2. If hit: return { balance, cachedAt, disclaimer: "Balance as of {time}" }
  3. If miss:
     → Read from Postgres replica
     → Cache with TTL 30s
     → Return

Event-driven invalidation:
  Transaction committed → DEL balance_display:{accountId}
  → Next balance read hits Postgres replica
  → Refreshed in cache
```

**UI guidance**: Display "Balance updated 10 seconds ago" rather than pretending it's real-time. Customers understand — ATMs have the same caveat.

**Regulatory note**: Some regulators require that displayed balance matches ledger balance at point of display. If that's the case: skip cache for balance, or cache only within 1s TTL.

---

## Layer 2: Transaction History Cache

Recent transactions (last 30 days) cached per user:

```
Key: txn_history:{accountId}:{year_month}
TTL: 60s
Payload: JSON array of recent transactions (max 100)

On new transaction:
  → DEL txn_history:{accountId}:{year_month}
  → Next request re-loads from Postgres replica

For statements > 30 days:
  → Generated async from Postgres (no cache)
  → PDF stored in S3 for 7 years
  → Large historical queries go to S3/archive, not live DB
```

---

## Layer 3: Reference Data Cache

Banking has large volumes of rarely-changing reference data:

```
IFSC codes: GET ifsc:{ifsc_code} → branch details
  TTL: 86400s (24 hours) — IFSC codes change infrequently
  Source: RBI IFSC master updated monthly
  
Interest rates: GET interest_rate:{product_code}:{tier}
  TTL: 3600s (1 hour)
  Event-invalidation: on rate change announcement
  
Fee structures: GET fee_schedule:{product}:{transaction_type}
  TTL: 3600s
  
Currency codes, country codes: TTL: 86400s (static master data)
```

Reference data caching reduces DB load significantly — thousands of transactions reference the same IFSC/rate data.

---

## Layer 4: Session Cache

Banking sessions: short TTL, mandatory re-auth on idle:

```
Key: session:{sessionId}
TTL: 900s (15 minutes idle timeout — regulatory requirement for internet banking)
Payload: { userId, accountIds, roles, authLevel, deviceId, ipAddress }

On activity: EXPIRE session:{sessionId} 900 (reset TTL)
On logout: DEL session:{sessionId}
On MFA completion: UPDATE session with elevated auth level
On idle timeout: session expired → user must re-login
```

Session data in Redis: sub-millisecond auth checks on every API call. Without Redis: DB hit on every request.

---

## Layer 5: Maker-Checker Pending Queue

Pending approvals must NOT be cached (must be real-time for checkers):

However, the list of pending requests per checker role can be cached briefly:

```
Key: pending_queue:{role}:{branchId}
TTL: 5s (very short — checkers must see new submissions quickly)
Payload: count of pending requests, list of request IDs

On new maker submission: DEL pending_queue:{role}:{branchId}
On checker action: DEL pending_queue:{role}:{branchId}
```

This reduces polling load on DB. 5s staleness in queue count is acceptable.

---

## OTP Cache

OTP validation requires fast, expiring storage — Redis ideal:

```
Key: otp:{userId}:{purpose}
TTL: 300s (5 minutes)
Value: { hashedOtp, attempts: 0, issuedAt }

On OTP issue: SET with TTL (auto-expires)
On validation attempt: check + increment attempts
If attempts > 3: DEL key (invalidate OTP, force re-issue)
On successful use: DEL key (one-time use)
```

---

## Rate Limiting (Redis Token Bucket)

Banking-specific rate limits:

```
Transfer rate limit:
  Key: rate:transfer:{userId}
  ZADD: add current_timestamp (score) with request_id
  ZREMRANGEBYSCORE: remove entries older than 60s
  ZCARD: count entries in last 60s
  If > 10: reject with 429

OTP rate limit:
  Key: rate:otp:{mobile}
  Limit: 5 OTPs per hour (per mobile number)
  TTL: 3600s
```

---

## Audit Implications of Caching

**Critical**: if a cached value is shown to the customer, log which version was shown:

```json
{
  "event": "BALANCE_DISPLAYED",
  "accountId": "ACC-123",
  "balanceDisplayed": 150000,
  "dataSource": "REDIS_CACHE",
  "cachedAt": "2026-01-15T10:23:10Z",
  "displayedAt": "2026-01-15T10:23:40Z",
  "staleness_seconds": 30
}
```

If customer disputes "wrong balance shown" → audit log proves exactly what was shown and when. This protects the bank.

---

## Redis Configuration for Banking

### Persistence (Non-Negotiable)

```
appendonly yes
appendfsync everysec    # Max 1s data loss (acceptable for cache)

# For session data: stricter
appendfsync always      # On every write for session:{sessionId}
```

Session loss = customer gets logged out unexpectedly. Not financial loss, but poor UX and compliance concern if mid-transaction.

### Eviction Policy

```
maxmemory-policy allkeys-lru
```

**Important**: unlike payment systems, banking cache can use LRU eviction — cached data (balance display, reference data) can always be re-fetched from Postgres. Session data should be in separate Redis instance with `noeviction` to prevent silent session loss.

### Two Redis Instances

```
redis-session (noeviction, appendfsync always):
  - Session data
  - OTP data
  - Rate limit keys

redis-cache (allkeys-lru, appendfsync everysec):
  - Balance display cache
  - Transaction history cache
  - Reference data cache
```

---

## Compliance Constraints on Caching

### RBI Internet Banking Guidelines

- Session timeout: maximum 15 minutes idle (enforced via Redis TTL)
- Transaction confirmation: must show current balance before debit (no cache)
- OTP: one-time use, maximum 5 minutes validity (enforced via Redis TTL + DEL on use)

### DPDP (Digital Personal Data Protection Act)

- Customer data in cache must be treated as personal data
- Cache logs (if any) cannot retain personal data longer than operational necessity
- Right to deletion: customer data deleted → invalidate all cache entries for that customer

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| 30s balance TTL | Reduces Postgres read load by 80% | Customer may see 30s stale balance |
| Event-driven cache invalidation | Near-real-time on transaction | Requires reliable event delivery |
| No cache for compliance flags | Always current | Additional DB hit on every auth-sensitive API |
| Separate session Redis (noeviction) | No silent session loss | Two Redis clusters to manage |
| Log cache metadata in audit | Dispute protection | Additional audit log volume |

---

## Interview Discussion Points

- **"Can you cache account balance in a banking system?"** → Yes, for display only, with short TTL and event-driven invalidation. No, for any authorization decision (debit, transfer). Explain the regulatory distinction.
- **"How do you handle the Redis TTL expiry during a session?"** → EXPIRE command reset on every activity (sliding window). If genuinely idle: session expired per RBI mandate. Users must re-login. No workarounds.
- **"What if Redis loses OTP data?"** → User OTP no longer valid; must request new OTP. No financial loss — OTP is access control only. Brief inconvenience during Redis recovery.
- **"How do you ensure cached balance matches regulatory requirements?"** → Log every cache read with staleness timestamp. If regulator audits: show audit log of what was displayed and when. For strict regulators: reduce TTL to 1s or disable balance cache.
- **"What's the staleness risk with balance caching?"** → Max 30s stale. Event-driven invalidation brings it to < 1s in practice for most cases. Edge case: transaction from another channel (ATM) — ATM systems trigger balance invalidation events through CBS (Core Banking System) integration.
