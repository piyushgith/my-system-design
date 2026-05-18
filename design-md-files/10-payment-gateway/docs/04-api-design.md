# 04 — API Design: Payment Gateway / Wallet System

## Objective

Define the REST API contract for the Payment Gateway and Wallet system. Establish versioning strategy, idempotency requirements, pagination, error handling, webhook design, and API evolution principles. Every API decision here has security, reliability, and developer experience implications.

---

## 1. API Design Principles

1. **Idempotency is mandatory** on all state-mutating endpoints — merchants must be able to safely retry without fear of double charges.
2. **REST over HTTP/2** for external merchant APIs. gRPC for internal service-to-service.
3. **Stable versioning** — breaking changes require a new major version; never break existing integrations.
4. **Meaningful error responses** — every 4xx and 5xx must return a machine-readable error code alongside a human-readable message.
5. **Pagination on all list endpoints** — cursor-based pagination for consistency under concurrent writes.
6. **Webhook signatures** — all outbound webhooks are HMAC-SHA256 signed so merchants can verify authenticity.
7. **Partial success is explicit** — async payment flows return 202 Accepted with a payment_id for subsequent polling.

---

## 2. API Versioning Strategy

**Strategy:** URI path versioning (`/v1/`, `/v2/`)

**Rationale:**
- Header versioning (`Accept: application/vnd.gateway.v2+json`) is cleaner but harder to test in browsers and API tools.
- Query param versioning is cache-unfriendly.
- URI path versioning is explicit, unambiguous, and widely used in payment APIs (Stripe, Razorpay).

**Backward compatibility rules:**
- Adding new fields to response: non-breaking (merchants must tolerate unknown fields).
- Adding new optional request fields: non-breaking.
- Removing fields, changing types, changing semantics: breaking → new version.
- New endpoint in existing version: non-breaking.
- Changing error codes for existing errors: breaking.

**Version lifecycle:**
- V1 → GA: supported minimum 24 months after V2 GA.
- Deprecation notice: 6 months before sunset.
- Merchants receive sunset warnings in API response headers: `Sunset: Sat, 01 Jan 2028 00:00:00 GMT`.

---

## 3. Authentication & Authorization

**Merchant API:** API Key in `Authorization: Bearer sk_live_xxxx` header.
- `sk_live_xxxx` = live key (production payments)
- `sk_test_xxxx` = test key (sandbox)
- Key is hashed with bcrypt before storage; never stored in plaintext.

**User-facing (Wallet) API:** JWT Bearer token issued after OAuth2 login.
- Access token TTL: 15 minutes.
- Refresh token TTL: 30 days (rotated on use).

**Internal service-to-service:** mTLS with service certificates issued by internal CA.

---

## 4. Payment APIs

### 4.1 Create Payment

```
POST /v1/payments
Authorization: Bearer sk_live_xxxx
Idempotency-Key: merchant-order-12345

Request Body:
{
  "amount": 100000,           // in minor currency units (paise)
  "currency": "INR",
  "payment_method": {
    "type": "card",
    "card_token": "tok_abc123"
  },
  "capture_method": "automatic" | "manual",
  "description": "Order #12345",
  "metadata": {
    "order_id": "ord_12345",
    "customer_email": "user@example.com"
  },
  "three_d_secure": {
    "required": true | false | "auto"
  },
  "return_url": "https://merchant.com/payment/callback"
}

Response 200 (card payment, frictionless):
{
  "payment_id": "pay_xxxxxxxxxxx",
  "status": "authorized",
  "amount": 100000,
  "currency": "INR",
  "authorization_code": "ABC123",
  "payment_method": {
    "type": "card",
    "card": {
      "brand": "visa",
      "last4": "4242",
      "exp_month": 12,
      "exp_year": 2026
    }
  },
  "created_at": "2026-05-18T10:00:00Z",
  "metadata": { "order_id": "ord_12345" }
}

Response 202 (3DS challenge required):
{
  "payment_id": "pay_xxxxxxxxxxx",
  "status": "requires_action",
  "next_action": {
    "type": "redirect_to_url",
    "redirect_to_url": {
      "url": "https://acs.bank.com/3ds?token=xxx",
      "return_url": "https://merchant.com/payment/callback"
    }
  }
}

Response 402 (declined):
{
  "payment_id": "pay_xxxxxxxxxxx",
  "status": "declined",
  "error": {
    "code": "card_declined",
    "decline_code": "insufficient_funds",
    "message": "The card has insufficient funds."
  }
}
```

**Idempotency behavior:**
- `Idempotency-Key` header is required for this endpoint.
- If the same key is received again (within 24 hours), return the cached response.
- If the original request is still in progress, return 409 Conflict with `{"code": "idempotency_key_in_use"}`.

### 4.2 Capture Payment

```
POST /v1/payments/{payment_id}/capture
Idempotency-Key: merchant-capture-12345

Request Body (optional — for partial capture):
{
  "amount": 80000   // capture less than authorized amount
}

Response 200:
{
  "payment_id": "pay_xxxxxxxxxxx",
  "status": "captured",
  "amount_captured": 80000,
  "amount_authorized": 100000
}
```

### 4.3 Refund Payment

```
POST /v1/payments/{payment_id}/refunds
Idempotency-Key: merchant-refund-12345

Request Body:
{
  "amount": 50000,   // partial or full refund
  "reason": "customer_request" | "duplicate" | "fraudulent",
  "metadata": { "refund_note": "Customer changed mind" }
}

Response 200:
{
  "refund_id": "ref_xxxxxxxxxxx",
  "payment_id": "pay_xxxxxxxxxxx",
  "status": "pending" | "succeeded" | "failed",
  "amount": 50000,
  "currency": "INR",
  "created_at": "2026-05-18T10:05:00Z"
}
```

### 4.4 Get Payment

```
GET /v1/payments/{payment_id}

Response 200:
{
  "payment_id": "pay_xxxxxxxxxxx",
  "status": "captured",
  "amount": 100000,
  "amount_captured": 80000,
  "refunds": [
    { "refund_id": "ref_xxx", "amount": 20000, "status": "succeeded" }
  ],
  ...
}
```

### 4.5 List Payments (Merchant)

```
GET /v1/payments?limit=25&starting_after=pay_yyy&status=captured&created_gte=2026-05-01T00:00:00Z

Response 200:
{
  "data": [ ...payment objects... ],
  "has_more": true,
  "next_cursor": "pay_zzz"
}
```

---

## 5. Wallet APIs

### 5.1 Get Wallet Balance

```
GET /v1/wallet
Authorization: Bearer {user_jwt}

Response 200:
{
  "wallet_id": "wal_xxxxxxxxxxx",
  "balance": 50000,
  "currency": "INR",
  "kyc_tier": "MINIMUM" | "FULL",
  "limits": {
    "daily_transfer_remaining": 950000,
    "max_balance": 200000
  },
  "status": "active"
}
```

### 5.2 Top-Up Wallet

```
POST /v1/wallet/topup
Idempotency-Key: user-topup-unique-key

{
  "amount": 50000,
  "payment_method": {
    "type": "card",
    "card_token": "tok_xyz"
  }
}

Response 200:
{
  "topup_id": "top_xxxxxxxxxxx",
  "status": "completed",
  "amount": 50000,
  "new_balance": 100000,
  "payment_id": "pay_xxxxxxxxxxx"  // underlying payment created
}
```

### 5.3 Wallet Transfer

```
POST /v1/wallet/transfer
Idempotency-Key: user-transfer-unique-key

{
  "to_user_id": "usr_yyy",
  "amount": 10000,
  "description": "Lunch split"
}

Response 200:
{
  "transfer_id": "txf_xxxxxxxxxxx",
  "status": "completed",
  "amount": 10000,
  "from_balance": 90000,
  "to_user_id": "usr_yyy",
  "completed_at": "2026-05-18T10:10:00Z"
}
```

### 5.4 Withdraw from Wallet

```
POST /v1/wallet/withdraw
Idempotency-Key: user-withdrawal-unique-key

{
  "amount": 20000,
  "bank_account": {
    "ifsc": "HDFC0001234",
    "account_number": "123456789012",
    "account_name": "John Doe"
  },
  "transfer_mode": "IMPS" | "NEFT" | "UPI"
}

Response 202:
{
  "withdrawal_id": "wdr_xxxxxxxxxxx",
  "status": "processing",
  "amount": 20000,
  "estimated_arrival": "2026-05-18T12:00:00Z"
}
```

### 5.5 Wallet Transaction History

```
GET /v1/wallet/transactions?limit=25&starting_after=txf_yyy&type=transfer&from=2026-05-01

Response 200:
{
  "data": [
    {
      "transaction_id": "txf_xxx",
      "type": "transfer_sent" | "transfer_received" | "topup" | "withdrawal" | "payment",
      "amount": 10000,
      "direction": "debit" | "credit",
      "balance_after": 90000,
      "description": "Lunch split",
      "created_at": "2026-05-18T10:10:00Z"
    }
  ],
  "has_more": false
}
```

---

## 6. Webhook Design

### 6.1 Webhook Event Structure

```
POST https://merchant.com/webhook
Content-Type: application/json
X-Gateway-Signature: t=1716033600,v1=abc123def456...
X-Gateway-Event: payment.captured

{
  "event_id": "evt_xxxxxxxxxxx",
  "type": "payment.captured",
  "created": 1716033600,
  "data": {
    "payment_id": "pay_xxxxxxxxxxx",
    "status": "captured",
    "amount": 100000,
    ...
  }
}
```

### 6.2 Signature Verification

The signature is `HMAC-SHA256(timestamp + "." + payload, merchant_signing_secret)`.

Merchants verify:
1. Extract `t` (timestamp) and `v1` (signature) from header.
2. Reject if timestamp is > 5 minutes old (replay attack protection).
3. Compute HMAC and compare in constant time.

### 6.3 Webhook Delivery Guarantees

- **At-least-once delivery:** if no 2xx response within 30 seconds, retry.
- **Retry schedule:** 5s, 30s, 2m, 10m, 30m, 2h, 8h, 24h (exponential backoff with jitter).
- **Deduplication:** include `event_id` so merchants can deduplicate on their side.
- **Dead letter:** after 72 hours of failed delivery, move to dead letter and alert merchant.

### 6.4 Webhook Event Types

| Event Type | Trigger |
|---|---|
| `payment.authorized` | Card authorization approved |
| `payment.captured` | Payment captured |
| `payment.declined` | Payment declined |
| `payment.failed` | Payment failed (network error, timeout) |
| `refund.created` | Refund initiated |
| `refund.succeeded` | Refund completed |
| `refund.failed` | Refund failed |
| `chargeback.created` | Chargeback received from card network |
| `chargeback.updated` | Chargeback status changed |
| `settlement.created` | Settlement batch created |
| `settlement.paid` | Settlement funds sent |

---

## 7. Error Handling Standards

### HTTP Status Codes

| Code | Usage |
|---|---|
| 200 OK | Successful synchronous operation |
| 201 Created | Resource created (used sparingly — prefer 200 with resource in response) |
| 202 Accepted | Async operation initiated (UPI, withdrawal) |
| 400 Bad Request | Invalid request parameters |
| 401 Unauthorized | Missing or invalid credentials |
| 402 Payment Required | Payment declined or insufficient funds |
| 403 Forbidden | Valid credentials but insufficient permissions |
| 404 Not Found | Resource not found |
| 409 Conflict | Idempotency key in use; duplicate request with different params |
| 422 Unprocessable Entity | Request is valid JSON but fails business validation |
| 429 Too Many Requests | Rate limit exceeded |
| 500 Internal Server Error | Unexpected server error (never expose stack traces) |
| 502 Bad Gateway | Upstream acquirer/bank error |
| 503 Service Unavailable | Gateway is temporarily unavailable |

### Error Response Structure

```
{
  "error": {
    "code": "card_declined",                  // machine-readable, stable
    "decline_code": "insufficient_funds",     // sub-code (card errors)
    "message": "Your card has insufficient funds.",   // human-readable
    "param": "payment_method.card_number",    // field that caused error (if applicable)
    "doc_url": "https://docs.gateway.io/errors/card_declined",
    "request_id": "req_xxxxxxxxxxx"          // for support correlation
  }
}
```

### Key Error Codes

| Code | Meaning |
|---|---|
| `invalid_api_key` | API key is invalid or revoked |
| `idempotency_key_in_use` | Key is currently being used by another request |
| `amount_too_small` | Amount below minimum transaction value |
| `amount_too_large` | Amount exceeds limit |
| `card_declined` | Generic card decline |
| `card_expired` | Card expiry date has passed |
| `insufficient_funds` | Decline reason is insufficient funds |
| `fraud_blocked` | Transaction blocked by fraud rules |
| `wallet_limit_exceeded` | KYC limit would be breached |
| `payment_not_capturable` | Payment is not in AUTHORIZED status |
| `refund_exceeds_charge` | Refund amount exceeds captured amount |
| `rate_limit_exceeded` | Too many requests |

---

## 8. Rate Limiting

| API Tier | Limit |
|---|---|
| Test mode | 100 requests/minute per API key |
| Live mode — standard | 1,000 requests/minute per API key |
| Live mode — enterprise | Custom (configured per merchant) |
| Payment creation specifically | 100/minute per merchant (anti-abuse) |
| Wallet transfer | 20/minute per user |

Rate limit response:
```
HTTP 429 Too Many Requests
Retry-After: 30
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1716033660

{ "error": { "code": "rate_limit_exceeded", "message": "Too many requests. Retry after 30 seconds." } }
```

---

## 9. Pagination Strategy

**Cursor-based pagination** (not offset-based) for all list endpoints.

**Why cursor-based?**
- Offset pagination breaks under concurrent inserts (a new payment at page 1 shifts all subsequent pages).
- Cursor is stable — it points to a specific record, not a position.
- Consistent performance regardless of page depth (offset requires full scan to skip N rows).

**Cursor format:** Opaque string (base64-encoded `{id}:{timestamp}`) — clients must not parse it.

**Parameters:**
- `limit`: max 100, default 25.
- `starting_after`: cursor for next page (from previous response's last item ID).
- `ending_before`: cursor for previous page (for backward navigation).

---

## 10. Idempotency Implementation Details

**Key format:** Client-provided string, max 64 characters, unique per merchant + 24-hour window.

**Storage:** Redis hash `idempotency:{merchant_id}:{key}` with 24-hour TTL.

**Stored value:** `{request_hash, response_body, response_status, payment_id}`

**Deduplication logic:**
1. Compute SHA-256 of request body.
2. If key exists in Redis AND request hash matches → return stored response.
3. If key exists in Redis AND request hash does NOT match → 422 Unprocessable Entity (`idempotency_key_parameters_mismatch`).
4. If key does not exist → SETNX to acquire lock → process → store result.

**In-flight handling:** If the first request is still processing (SETNX succeeded but no result yet stored), subsequent requests get 409 Conflict.

---

## 11. API Evolution Strategy

| Change Type | Version Impact | Approach |
|---|---|---|
| New optional request field | None | Add field, default behavior unchanged |
| New response field | None | Clients must ignore unknown fields |
| New endpoint | None | Add to current version |
| Remove field | Breaking | New version, 6-month deprecation notice |
| Change field semantics | Breaking | New version |
| Change error code | Breaking | New version |
| Change auth mechanism | Breaking | New version with migration guide |

---

## 12. Overengineering Warning

- Do not implement GraphQL for the core payment API in V1 — payment APIs are action-oriented (RPC-style), not graph-traversal. GraphQL adds overhead without benefit here.
- Do not implement gRPC for merchant-facing APIs — merchants use REST; gRPC is for internal service communication.
- Do not implement HATEOAS — it adds response payload bloat without real navigability benefit in a payment API.

---

## 13. Interview-Level Discussion Points

- **"Why is the Idempotency-Key on the client, not server-generated?"** The client (merchant) generates it before making the request, typically tied to their order ID. This way, if the request times out before a response is received, the merchant can retry with the same key and get the same result — without knowing if the original request succeeded.
- **"How do you handle an idempotency key that comes in while the original request is still processing?"** Return 409 Conflict immediately. The client should poll the payment status endpoint instead of retrying the create endpoint.
- **"Why cursor-based pagination vs offset?"** Offset pagination is O(offset) in PostgreSQL — fetching page 100 of 25 results requires scanning 2,500 rows. Cursor-based uses an indexed range scan from the cursor point, always O(limit).
- **"How does webhook signature verification protect against replay attacks?"** The timestamp in the signature is validated against current time (reject if > 5 minutes old). An attacker capturing a valid webhook cannot replay it after 5 minutes.
- **"How does API versioning affect the database schema?"** API versioning is at the presentation layer. The domain model and database schema are version-agnostic — the API layer translates between API versions and the stable internal model.
