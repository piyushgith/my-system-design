# 04 — API Design: Banking Core System

## Objective

Define the REST API design for all major operations in the Banking Core System. Cover versioning strategy, idempotency, pagination, error handling standards, authorization requirements, and backward compatibility. All APIs are designed with regulatory auditability, idempotency safety, and consumer-grade reliability in mind.

---

## 1. API Design Principles

### 1.1 Core Principles
- **REST over HTTP/1.1** for external-facing APIs; **gRPC** for internal service-to-service (if extracted)
- **Resource-oriented URIs** — nouns, not verbs
- **Idempotency by design** — all mutation APIs require an idempotency key
- **Versioning via URI path** — `/v1/`, `/v2/` — explicit and stable
- **202 Accepted** for asynchronous operations — never block the client on long-running operations
- **Pagination required** on all list endpoints — no unbounded result sets
- **Audit trail** — every mutating request logs user identity, timestamp, IP, and correlation ID

### 1.2 Authorization Layer

Every API endpoint is annotated with the minimum required role:

| Role | Description |
|---|---|
| `CUSTOMER` | End customer via internet/mobile banking |
| `TELLER` | Branch-level staff |
| `BRANCH_MANAGER` | Branch manager — can approve teller-initiated operations |
| `SENIOR_MANAGER` | Regional/Senior management — can approve high-value operations |
| `BACK_OFFICE` | Operations team — reconciliation, exceptions |
| `COMPLIANCE_OFFICER` | AML, KYC review |
| `ADMIN` | System administration |
| `SYSTEM` | Internal batch/service-to-service calls |

---

## 2. API Versioning Strategy

**Approach**: URI-based versioning (`/api/v1/`, `/api/v2/`)

**Rationale**:
- Explicit and visible — clients always know exactly which version they're calling
- Easy to route at API gateway level — different versions can point to different service versions
- Simple to deprecate — sunset headers + deprecation notices for old versions

**Lifecycle**:
- A version is **current** for minimum 18 months after successor release
- Sunset announced 6 months in advance via `Deprecation` and `Sunset` response headers
- Breaking changes ALWAYS require a new version — never in-place for financial APIs

**Backward Compatibility Rules**:
- Adding new optional fields: allowed in same version
- Adding new endpoints: allowed in same version
- Changing field names, removing fields, changing semantics: requires new version
- Changing error codes: requires new version

---

## 3. Account APIs

### 3.1 Get Account Details
```
GET /api/v1/accounts/{accountId}
Authorization: Bearer {jwt}
Required Role: CUSTOMER (own account), TELLER, BRANCH_MANAGER
```

**Response**:
```json
{
  "accountId": "SAV-10000001",
  "accountType": "SAVINGS",
  "status": "ACTIVE",
  "currency": "INR",
  "currentBalance": 125000.00,
  "availableBalance": 120000.00,
  "liensTotal": 5000.00,
  "productCode": "SAV_BASIC",
  "openDate": "2021-05-12",
  "interestRate": 3.50,
  "minBalance": 10000.00,
  "overdraftLimit": 0.00,
  "kycStatus": "VERIFIED",
  "links": {
    "transactions": "/api/v1/accounts/SAV-10000001/transactions",
    "statement": "/api/v1/accounts/SAV-10000001/statements"
  }
}
```

---

### 3.2 Get Account Balance (High-Frequency Read)
```
GET /api/v1/accounts/{accountId}/balance
Authorization: Bearer {jwt}
Required Role: CUSTOMER, TELLER
```

**Response**: Served from Redis cache (TTL 30 seconds). Returns `balanceAsOf` timestamp.

```json
{
  "accountId": "SAV-10000001",
  "currentBalance": 125000.00,
  "availableBalance": 120000.00,
  "currency": "INR",
  "balanceAsOf": "2024-01-15T14:32:01Z"
}
```

---

### 3.3 Open Account (Maker-Checker Required)
```
POST /api/v1/accounts
Authorization: Bearer {jwt}
Required Role: TELLER
Idempotency-Key: {client-generated UUID}
```

**Request**:
```json
{
  "cifId": "CIF-10000001",
  "accountType": "SAVINGS",
  "productCode": "SAV_BASIC",
  "currency": "INR",
  "initialDeposit": 25000.00,
  "nomineeId": "NOM-001"
}
```

**Response** (if maker-checker required):
```json
{
  "approvalRequestId": "APR-20240115-001",
  "status": "PENDING_APPROVAL",
  "message": "Account opening request submitted for approval",
  "expiresAt": "2024-01-15T18:00:00Z"
}
```

---

## 4. Transaction APIs

### 4.1 Get Transaction History
```
GET /api/v1/accounts/{accountId}/transactions
Authorization: Bearer {jwt}
Required Role: CUSTOMER (own account), TELLER
Query Parameters:
  - fromDate (ISO 8601)
  - toDate (ISO 8601)
  - type (DEBIT|CREDIT|ALL)
  - page (default: 0)
  - size (default: 25, max: 100)
  - sort (postingDate,desc)
```

**Response** (cursor-based for forward-only paging; offset-based for banking statements):
```json
{
  "accountId": "SAV-10000001",
  "transactions": [
    {
      "txnId": "TXN-20240115-ABC123",
      "type": "DEBIT",
      "amount": 5000.00,
      "currency": "INR",
      "narration": "IMPS/NEFT Transfer to XYZ Bank",
      "referenceNumber": "NEFT20240115XXXXXX",
      "postingDate": "2024-01-15",
      "valueDate": "2024-01-15",
      "runningBalance": 120000.00
    }
  ],
  "pagination": {
    "page": 0,
    "size": 25,
    "totalElements": 342,
    "totalPages": 14,
    "hasNext": true
  }
}
```

**Pagination Strategy**:
- **Keyset pagination** for real-time transaction feeds (cursor = last txn timestamp + txnId)
- **Offset pagination** for statement generation (predictable page numbers for PDF rendering)
- Keyset is preferred for large datasets (avoids deep offset queries)

---

### 4.2 Internal Transfer (Between Own Accounts)
```
POST /api/v1/transactions/transfer
Authorization: Bearer {jwt}
Required Role: CUSTOMER, TELLER
Idempotency-Key: {required}
```

**Request**:
```json
{
  "fromAccountId": "SAV-10000001",
  "toAccountId": "FD-20000001",
  "amount": 50000.00,
  "currency": "INR",
  "narration": "FD top-up",
  "valueDate": "2024-01-15"
}
```

**Response**:
```json
{
  "txnId": "TXN-20240115-XYZ999",
  "status": "POSTED",
  "fromAccountBalance": 70000.00,
  "toAccountBalance": 150000.00,
  "postingDate": "2024-01-15",
  "valueDate": "2024-01-15"
}
```

---

## 5. Payment APIs

### 5.1 Initiate IMPS Payment
```
POST /api/v1/payments/imps
Authorization: Bearer {jwt}
Required Role: CUSTOMER, TELLER
Idempotency-Key: {required}
```

**Request**:
```json
{
  "sourceAccountId": "SAV-10000001",
  "beneficiary": {
    "accountNumber": "987654321012",
    "ifscCode": "HDFC0001234",
    "name": "Rajesh Kumar"
  },
  "amount": 25000.00,
  "currency": "INR",
  "purpose": "FAMILY_MAINTENANCE",
  "narration": "Monthly rent"
}
```

**Response**:
```json
{
  "paymentId": "PMT-IMPS-20240115-001",
  "status": "INITIATED",
  "message": "Payment processing. Track with paymentId.",
  "estimatedSettlement": "within 30 seconds"
}
```

**Status Polling**:
```
GET /api/v1/payments/{paymentId}/status
```

---

### 5.2 NEFT Payment (Batch)
```
POST /api/v1/payments/neft
Required Role: CUSTOMER, TELLER
Idempotency-Key: {required}
```

NEFT has batch windows (every 30 minutes during banking hours). Response includes estimated settlement window.

---

### 5.3 RTGS Payment (High-Value, Approval Required)
```
POST /api/v1/payments/rtgs
Required Role: TELLER (initiates), BRANCH_MANAGER (approves)
Idempotency-Key: {required}
```

RTGS requires minimum ₹2,00,000. Payments above ₹10,00,000 require BRANCH_MANAGER approval via maker-checker flow before submission to RBI.

---

## 6. Approval Workflow APIs

### 6.1 List Pending Approvals
```
GET /api/v1/approvals/pending
Authorization: Bearer {jwt}
Required Role: BRANCH_MANAGER, SENIOR_MANAGER
Query: type, fromDate, toDate, page, size
```

### 6.2 Approve or Reject
```
POST /api/v1/approvals/{approvalRequestId}/decide
Authorization: Bearer {jwt}
Required Role: BRANCH_MANAGER, SENIOR_MANAGER
Idempotency-Key: {required}
```

**Request**:
```json
{
  "decision": "APPROVE",
  "comments": "Verified customer identity and documentation",
  "mfaToken": "123456"
}
```

**Response**:
```json
{
  "approvalRequestId": "APR-20240115-001",
  "decision": "APPROVED",
  "decidedBy": "MGR-001",
  "decidedAt": "2024-01-15T15:45:00Z",
  "effectResult": {
    "accountId": "SAV-10000002",
    "status": "ACTIVE"
  }
}
```

**Note**: The MFA token is required for checker action — ensures the checker is physically present and authenticated at the time of approval, not just logged in.

---

## 7. Compliance APIs

### 7.1 AML Alert Queue (Compliance Officer)
```
GET /api/v1/compliance/alerts
Required Role: COMPLIANCE_OFFICER
```

### 7.2 Submit STR (Suspicious Transaction Report)
```
POST /api/v1/compliance/str
Required Role: COMPLIANCE_OFFICER
Idempotency-Key: {required}
```

---

## 8. Statement APIs

### 8.1 Request Account Statement
```
POST /api/v1/accounts/{accountId}/statements/request
Required Role: CUSTOMER, TELLER
```

**Request**:
```json
{
  "fromDate": "2024-01-01",
  "toDate": "2024-01-31",
  "format": "PDF",
  "deliveryMethod": "EMAIL"
}
```

**Response**: 202 Accepted — statement is generated asynchronously and delivered via email.

```json
{
  "requestId": "STMT-REQ-001",
  "status": "QUEUED",
  "estimatedDelivery": "within 5 minutes"
}
```

---

## 9. Idempotency Design

**How It Works**:
1. Client generates a UUID `Idempotency-Key` and includes it as a request header
2. Server checks a Redis/DB idempotency store for this key
3. If key exists → return the stored response (do not re-process)
4. If key absent → process request, store response with key + TTL (24 hours)
5. Key is scoped to the user ID to prevent cross-user replay attacks

**What Idempotency Covers**:
- Network retries (client retries due to timeout — server has already processed)
- Client-side double-submit (user clicks "Pay" twice)
- Load balancer retry after upstream 5xx

**What It Does NOT Cover**:
- Business logic re-execution (sending same payment twice with different idempotency keys = two payments)
- This is a feature, not a bug — idempotency is about request dedup, not business-level dedup

---

## 10. Error Handling Standards

### Error Response Format
```json
{
  "error": {
    "code": "INSUFFICIENT_FUNDS",
    "message": "Available balance (₹15,000) is less than requested amount (₹25,000)",
    "details": {
      "accountId": "SAV-10000001",
      "availableBalance": 15000.00,
      "requestedAmount": 25000.00
    },
    "correlationId": "corr-abc123-xyz",
    "timestamp": "2024-01-15T14:32:01Z",
    "path": "/api/v1/payments/imps"
  }
}
```

### HTTP Status Code Conventions

| Status | Usage |
|---|---|
| 200 OK | Successful synchronous read |
| 201 Created | Resource successfully created |
| 202 Accepted | Async operation queued |
| 400 Bad Request | Validation failure, missing fields |
| 401 Unauthorized | Invalid or expired JWT |
| 403 Forbidden | Authenticated but lacks permission |
| 404 Not Found | Resource does not exist |
| 409 Conflict | Idempotency key already used with different payload; or business conflict (account already closed) |
| 422 Unprocessable Entity | Syntactically valid but business-rule failure (insufficient funds, KYC expired) |
| 429 Too Many Requests | Rate limit exceeded |
| 503 Service Unavailable | Rail system (NEFT/RTGS) temporarily unavailable |

### Domain Error Codes

| Code | Meaning |
|---|---|
| `INSUFFICIENT_FUNDS` | Balance < requested amount |
| `ACCOUNT_FROZEN` | Account is in frozen state |
| `KYC_EXPIRED` | Customer KYC not current |
| `LIEN_EXCEEDS_BALANCE` | Lien amount > available balance |
| `PAYMENT_RAIL_UNAVAILABLE` | NEFT/RTGS/IMPS system down |
| `APPROVAL_REQUIRED` | Operation needs maker-checker |
| `DUPLICATE_TRANSACTION` | Idempotency key collision |
| `AMOUNT_BELOW_MIN` | RTGS minimum amount not met |
| `DAILY_LIMIT_EXCEEDED` | Customer's daily transfer limit hit |
| `BENEFICIARY_NOT_VERIFIED` | Beneficiary not on registered list |

---

## 11. Rate Limiting

| API Category | Limit | Window |
|---|---|---|
| Balance inquiry (CUSTOMER) | 60 requests | per minute |
| Payment initiation (CUSTOMER) | 10 requests | per minute |
| Payment initiation (TELLER) | 100 requests | per minute |
| Statement request | 5 requests | per hour |
| Login attempts | 5 attempts | per 15 minutes |
| Approval decisions | 50 decisions | per minute |

Rate limiting is enforced at the API Gateway (Kong) using Redis-backed counters. Exceeded limits return 429 with `Retry-After` header.

---

## 12. API Evolution Strategy

### Expand-Contract Pattern for DB Changes
When an API field maps to a DB column being renamed:
1. **Expand**: Add new column alongside old (both active)
2. **Migrate**: Write new code to populate new column
3. **Switch**: API reads from new column; old column in deprecated state
4. **Contract**: Remove old column after all clients updated

### API Sunset Process
- Add `Deprecation: true` header to deprecated endpoints
- Add `Sunset: {date}` header with planned removal date
- Log warning in server when deprecated endpoint is called (track active consumers)
- Remove only after usage metrics confirm zero traffic

---

## 13. gRPC for Internal Communication (Future)

If specific modules are extracted to microservices:
- Ledger Service → Account Service: gRPC with proto-defined contracts
- Higher performance (binary protocol), strong typing via protobuf
- Bidirectional streaming for real-time balance sync
- Service reflection for service discovery

---

## 14. Interview-Level Discussion Points

**Q: Why not GraphQL for banking APIs?**
A: GraphQL's flexibility (client-defined queries) is a security risk in banking. A customer could potentially craft a query that retrieves more data than intended. REST with explicit, auditable endpoints is safer. GraphQL is appropriate for internal reporting dashboards where the consumer is a controlled internal tool.

**Q: How do you handle the case where the client receives a 504 timeout but the payment was actually processed?**
A: The idempotency key resolves this. The client retries with the same idempotency key. If the server processed the payment, it returns the stored success response — no double debit. If the server did not process it (the timeout was on the client's network), the payment is processed fresh. The client must never assume failure on timeout — always retry with the same idempotency key.

**Q: How would you version a breaking change to the transaction response schema?**
A: Release `/api/v2/accounts/{id}/transactions` alongside v1. v2 has the new schema. Announce v1 sunset 6 months in advance with the Deprecation + Sunset headers. Mobile app teams update their clients to use v2 within the window. After the sunset date, v1 returns 410 Gone with a migration URL.

**Q: What is 422 vs 400 in a banking context?**
A: 400 is a syntactic failure — the request is malformed (missing required field, wrong date format). 422 is a semantic failure — the request is syntactically valid but the business cannot process it (insufficient funds, account frozen). This distinction matters for client error handling. 400 = fix your request structure. 422 = the business state prevents this operation. Banks often merge these, which loses meaningful information for client developers.
