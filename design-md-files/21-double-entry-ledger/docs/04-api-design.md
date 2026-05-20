# 04 — API Design: Double-Entry Ledger Service

---

## Objective

Define the REST and gRPC API surface for the double-entry ledger service, covering posting creation, balance queries, account management, and reconciliation. Address idempotency, versioning, pagination, and error standards.

---

## API Style Decision

**Internal service API:** gRPC for synchronous service-to-service calls (posting, balance reads). Performance-critical — binary protocol, contract-first schema via protobuf.

**Operations/Admin API:** REST for human-accessible endpoints — reconciliation, GL reports, account management. Easier to consume via browsers, curl, and finance tooling.

**Why gRPC for the hot path?**
- 5x–10x lower serialization overhead vs JSON for high-frequency posting calls
- Strongly typed contracts via protobuf — no schema drift
- Bidirectional streaming support for future real-time balance feeds
- Native HTTP/2 — multiplexing reduces connection overhead at 5,000 RPS

---

## API Versioning Strategy

- gRPC: package versioning in protobuf (`com.fintech.ledger.v1`, `com.fintech.ledger.v2`)
- REST: URL path versioning (`/api/v1/accounts`, `/api/v2/accounts`)
- Deprecation: old version supported for minimum 6 months with `Deprecation` header in responses
- Schema registry governs Kafka event schema versions separately

---

## gRPC API (Hot Path)

### Service: `LedgerService`

```protobuf
service LedgerService {
    // Core posting operations
    rpc PostTransaction(PostTransactionRequest) returns (PostTransactionResponse);
    rpc ReversePosting(ReversePostingRequest) returns (ReversePostingResponse);

    // Balance queries
    rpc GetBalance(GetBalanceRequest) returns (GetBalanceResponse);
    rpc GetBalanceAtTime(GetBalanceAtTimeRequest) returns (GetBalanceAtTimeResponse);

    // Streaming (V2)
    rpc WatchBalance(WatchBalanceRequest) returns (stream BalanceUpdate);
}
```

---

## REST API (Admin / Operations)

### Base URL: `/api/v1`

---

### Accounts

#### `POST /api/v1/accounts`

Create a new account in the chart of accounts.

**Request:**
```json
{
  "account_code": "1001-SAVINGS-INR",
  "account_name": "Customer Savings Account",
  "account_type": "ASSET",
  "currency": "INR",
  "owner_id": "usr_abc123",
  "owner_type": "USER"
}
```

**Response `201 Created`:**
```json
{
  "account_id": "acc_uuid",
  "account_code": "1001-SAVINGS-INR",
  "account_type": "ASSET",
  "normal_balance": "DEBIT",
  "currency": "INR",
  "status": "ACTIVE",
  "created_at": "2024-01-15T10:00:00Z"
}
```

#### `GET /api/v1/accounts/{account_id}`

Fetch account metadata (not balance — balance is a separate endpoint).

#### `PATCH /api/v1/accounts/{account_id}/status`

Freeze or close an account. Requires elevated privilege.

**Request:**
```json
{ "status": "FROZEN", "reason": "AML hold" }
```

---

### Postings

#### `POST /api/v1/postings`

Create a journal posting (atomic multi-leg). Idempotent by `idempotency_key`.

**Request:**
```json
{
  "idempotency_key": "payment:pay_xyz789",
  "reference_type": "PAYMENT",
  "reference_id": "pay_xyz789",
  "effective_at": "2024-01-15T10:30:00Z",
  "description": "Fund transfer from savings to current",
  "legs": [
    {
      "account_id": "acc_source_uuid",
      "direction": "CREDIT",
      "amount": 500000,
      "currency": "INR",
      "description": "Debit source account"
    },
    {
      "account_id": "acc_dest_uuid",
      "direction": "DEBIT",
      "amount": 500000,
      "currency": "INR",
      "description": "Credit destination account"
    }
  ],
  "metadata": {
    "initiated_by": "usr_abc123",
    "channel": "MOBILE_APP"
  }
}
```

**Response `201 Created`:**
```json
{
  "posting_id": "post_uuid",
  "idempotency_key": "payment:pay_xyz789",
  "status": "POSTED",
  "effective_at": "2024-01-15T10:30:00Z",
  "legs": [
    {
      "entry_id": "entry_uuid_1",
      "account_id": "acc_source_uuid",
      "direction": "CREDIT",
      "amount": 500000,
      "currency": "INR"
    },
    {
      "entry_id": "entry_uuid_2",
      "account_id": "acc_dest_uuid",
      "direction": "DEBIT",
      "amount": 500000,
      "currency": "INR"
    }
  ],
  "created_at": "2024-01-15T10:30:00.123Z"
}
```

**Idempotent Repeat:**
- Same `idempotency_key` returns `200 OK` with the original posting response
- HTTP header: `Idempotency-Result: HIT`

#### `POST /api/v1/postings/{posting_id}/reverse`

Create a reversal posting. Flips all legs direction. Atomic.

**Request:**
```json
{
  "idempotency_key": "reversal:pay_xyz789",
  "reason": "Payment cancelled by user",
  "effective_at": "2024-01-15T11:00:00Z"
}
```

**Response `201 Created`:**
- Returns the reversal posting in the same format as a regular posting
- Original posting `status` updated to `REVERSED`

#### `GET /api/v1/postings/{posting_id}`

Fetch a single posting with all legs.

#### `GET /api/v1/postings?account_id={id}&from={date}&to={date}&cursor={cursor}&limit={n}`

List postings for an account within a date range. Cursor-based pagination.

---

### Balances

#### `GET /api/v1/accounts/{account_id}/balance`

Fetch current balance (from snapshot + cache).

**Response `200 OK`:**
```json
{
  "account_id": "acc_uuid",
  "balance": 1250000,
  "currency": "INR",
  "normal_balance_direction": "DEBIT",
  "display_balance": "12,500.00",
  "snapshot_as_of": "2024-01-15T10:30:00.123Z",
  "freshness": "SNAPSHOT"
}
```

`freshness` values: `CACHE`, `SNAPSHOT`, `COMPUTED` (computed from raw journal — fallback only)

#### `GET /api/v1/accounts/{account_id}/balance?as_of={timestamp}`

Point-in-time balance query. Cannot use snapshot cache — must aggregate journal up to timestamp.

**Response:**
- Same structure as above, with `freshness: COMPUTED` and `as_of` echoed back

---

### Reconciliation (Admin)

#### `POST /api/v1/reconciliation/runs`

Start a reconciliation run comparing ledger to external data source.

**Request:**
```json
{
  "account_id": "acc_uuid",
  "external_source": "BANK_STATEMENT",
  "from_date": "2024-01-01",
  "to_date": "2024-01-31",
  "external_file_reference": "s3://recon-files/jan-2024-statement.csv"
}
```

**Response `202 Accepted`:**
```json
{
  "run_id": "recon_uuid",
  "status": "PROCESSING",
  "estimated_completion": "2024-01-15T12:00:00Z"
}
```

#### `GET /api/v1/reconciliation/runs/{run_id}`

Poll reconciliation run status and fetch discrepancies.

---

## Idempotency Design

| Layer | Mechanism |
|---|---|
| HTTP | `idempotency_key` field in request body (required for all write operations) |
| Redis | `idempotency:{key}` → `posting_id` with 24h TTL for fast short-circuit |
| Database | UNIQUE constraint on `postings.idempotency_key` — prevents race condition on cache miss |
| Response | On duplicate: `200 OK` with original response + `Idempotency-Result: HIT` header |

**Why body field, not header?**

The `Idempotency-Key` header approach (Stripe-style) is acceptable for REST. The body field approach is used here because:
- gRPC does not have idiomatic headers — body field is consistent across both protocols
- Caller explicitly composes the key with domain context (e.g., `payment:pay_xyz789`) which is meaningful for debugging

**Idempotency Key Format:**
- `{reference_type}:{reference_id}` — e.g., `payment:pay_xyz789`, `disbursement:dis_abc`
- Caller owns key generation — must be stable across retries
- Keys are domain-scoped to prevent cross-domain collision

---

## Pagination Strategy

All list endpoints use cursor-based pagination (not offset-based).

**Why cursor over offset?**

The journal_entries table is append-only and grows continuously. Offset pagination degrades to O(n) full scans as offset increases. Cursor pagination uses the last seen `entry_id` or `posting_id` as a seek predicate — O(log n) via index.

**Pattern:**
```
GET /api/v1/postings?account_id=acc_uuid&limit=50&cursor=post_abc123
Response includes:
  "next_cursor": "post_def456"
  "has_more": true
```

Cursor encodes: `base64(posting_id + created_at)` — stable across concurrent inserts.

---

## Error Handling Standards

| HTTP Status | Meaning | Example |
|---|---|---|
| `400 Bad Request` | Validation failure | debit != credit sum |
| `404 Not Found` | Account not found | invalid account_id in legs |
| `409 Conflict` | Account is FROZEN or CLOSED | posting to frozen account |
| `422 Unprocessable Entity` | Business rule violation | negative amount, unsupported currency |
| `429 Too Many Requests` | Rate limit exceeded | caller throttled |
| `500 Internal Server Error` | Unexpected failure | DB unreachable |
| `503 Service Unavailable` | Circuit breaker open | downstream DB unavailable |

**Error Response Body:**
```json
{
  "error_code": "POSTING_INVARIANT_VIOLATION",
  "message": "Debit sum (500000 INR) does not equal credit sum (499000 INR). Imbalance: 1000 INR",
  "request_id": "req_uuid",
  "timestamp": "2024-01-15T10:30:00Z",
  "details": {
    "debit_sum": 500000,
    "credit_sum": 499000,
    "currency": "INR"
  }
}
```

---

## Rate Limiting

| Caller Type | Posting Limit | Balance Read Limit |
|---|---|---|
| Internal services (trusted) | 10,000 RPS | 100,000 RPS |
| Operations dashboard | 100 RPS | 1,000 RPS |
| External partners (if any) | 500 RPS | 5,000 RPS |

Rate limiting enforced at API Gateway layer using Redis sliding window counters.

---

## API Evolution Strategy

| Change Type | Strategy |
|---|---|
| Add optional field to request | Backward compatible — no version bump |
| Add optional field to response | Backward compatible — consumers must ignore unknown fields |
| Remove field from response | Deprecate with `X-Deprecated-Field` header, remove after 6 months |
| Change field semantics | Breaking — requires `v2` endpoint with migration guide |
| Add new endpoint | Non-breaking — add to existing version |
| Change Kafka event schema | Schema registry BACKWARD compatibility mode |

---

## Interview Discussion Points

- **Why idempotency key in the body vs HTTP header?** Consistency across gRPC and REST. gRPC has metadata (headers), but body fields are more explicit and debuggable. Stripe uses the header approach — both are valid; pick one and be consistent
- **How do you prevent an attacker from posting with someone else's idempotency key?** Idempotency keys are scoped by the authenticated caller. `caller_id:reference_type:reference_id` — if caller A tries to use caller B's key, the existing posting_id belongs to caller B and the auth check on the original posting will reject the lookup
- **Why not return 202 Accepted for postings?** The ledger is synchronous-first — the caller (payment service) needs the posting confirmation before it can respond to its own client. Async posting would require the payment service to poll, adding latency and complexity to the critical path
- **How does cursor pagination handle time-based queries?** The cursor encodes the `posting_id` (UUID v7 — time-ordered) and the `effective_at`. For date-range queries, the cursor serves as a seek key into the composite index `(account_id, effective_at, posting_id)`, enabling O(log n) seeks
