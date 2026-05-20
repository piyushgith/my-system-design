# 04 — API Design: Loan Origination & Servicing System

## Objective

Define REST APIs for loan origination, servicing, and back-office operations. Establish idempotency, versioning, pagination, and error handling standards appropriate for a financial lending platform.

---

## API Base URLs

```
Borrower API:      https://api.lender.com/v1
Back-office API:   https://backoffice.lender.com/v1
Webhook receiver:  https://webhooks.lender.com/v1
```

---

## Versioning Strategy

URL-based major versioning (`/v1`, `/v2`). Header-based (`X-API-Version`) considered but rejected: URL versioning is more visible in logs, proxies, and monitoring. Minor changes are backward-compatible (new optional fields only).

---

## Idempotency Strategy

All state-changing endpoints accept `Idempotency-Key` header (client-generated UUID):

```
Idempotency-Key: client-uuid-abc123
```

- Server stores `{idempotency_key} → {response}` in Redis with 24-hour TTL
- Duplicate request within 24 hours returns the original response
- Critical for: application submission, disbursement trigger, repayment recording

---

## API: Loan Origination (Borrower-Facing)

### Create Application (DRAFT)

```
POST /v1/applications
Idempotency-Key: {client-uuid}

Body:
{
  "productType": "PERSONAL_LOAN",
  "requestedAmount": { "amount": "500000.00", "currency": "INR" },
  "requestedTenureMonths": 24,
  "purpose": "MEDICAL_EXPENSES",
  "monthlyIncome": { "amount": "75000.00", "currency": "INR" }
}

Response 201:
{
  "applicationId": "uuid",
  "status": "DRAFT",
  "createdAt": "2024-01-15T10:00:00Z"
}
```

### Upload Document

```
POST /v1/applications/{applicationId}/documents
Content-Type: multipart/form-data

Fields:
  documentType: AADHAAR | PAN | SALARY_SLIP | BANK_STATEMENT | FORM_16
  file: (binary)

Response 201:
{
  "documentId": "uuid",
  "documentType": "SALARY_SLIP",
  "status": "UPLOADED",
  "s3Key": "documents/app-uuid/salary-slip-uuid.pdf"
}
```

Documents stored in S3. Only metadata in PostgreSQL (s3Key, documentType, uploadedAt).

### Submit Application

```
POST /v1/applications/{applicationId}/submit
Idempotency-Key: {client-uuid}

Response 202:
{
  "applicationId": "uuid",
  "status": "SUBMITTED",
  "expectedDecisionBy": "2024-01-16T10:00:00Z"
}
```

Triggers async underwriting workflow. Returns immediately with 202.

### Get Application Status

```
GET /v1/applications/{applicationId}

Response 200:
{
  "applicationId": "uuid",
  "status": "UNDER_REVIEW",
  "productType": "PERSONAL_LOAN",
  "requestedAmount": { "amount": "500000.00", "currency": "INR" },
  "submittedAt": "2024-01-15T10:05:00Z",
  "expectedDecisionBy": "2024-01-16T10:05:00Z",
  "offer": null
}
```

### Get Loan Offer

```
GET /v1/applications/{applicationId}/offer

Response 200:
{
  "offerId": "uuid",
  "approvedAmount": { "amount": "500000.00", "currency": "INR" },
  "interestRate": 12.5,
  "tenureMonths": 24,
  "emiAmount": { "amount": "23718.45", "currency": "INR" },
  "processingFee": { "amount": "5000.00", "currency": "INR" },
  "totalInterest": { "amount": "69242.80", "currency": "INR" },
  "offerValidUntil": "2024-01-20T10:00:00Z",
  "offerDocumentUrl": "https://..."
}
```

### Accept Offer

```
POST /v1/offers/{offerId}/accept
Idempotency-Key: {client-uuid}

Body:
{
  "disbursementAccountNumber": "9876543210",
  "disbursementIfscCode": "SBIN0001234",
  "nachMandateConsent": true,
  "eSignatureReference": "esign-uuid"
}

Response 202:
{
  "offerId": "uuid",
  "status": "ACCEPTED",
  "estimatedDisbursementBy": "2024-01-15T14:00:00Z"
}
```

---

## API: Loan Servicing (Borrower-Facing)

### Get Loan Account Summary

```
GET /v1/loans/{loanAccountId}

Response 200:
{
  "loanAccountId": "uuid",
  "loanAccountNumber": "LOAN-2024-000123",
  "status": "ACTIVE",
  "originalPrincipal": { "amount": "500000.00", "currency": "INR" },
  "outstandingPrincipal": { "amount": "423145.20", "currency": "INR" },
  "nextEmiDueDate": "2024-02-01",
  "nextEmiAmount": { "amount": "23718.45", "currency": "INR" },
  "dpd": 0,
  "tenureRemainingMonths": 18
}
```

### Get Amortization Schedule

```
GET /v1/loans/{loanAccountId}/schedule

Response 200:
{
  "loanAccountId": "uuid",
  "schedule": [
    {
      "installmentNumber": 1,
      "dueDate": "2024-02-01",
      "openingPrincipal": "500000.00",
      "emiAmount": "23718.45",
      "principalComponent": "18551.78",
      "interestComponent": "5166.67",
      "closingPrincipal": "481448.22",
      "status": "PAID",
      "paidOn": "2024-02-01"
    },
    ...
  ]
}
```

### Get Repayment History

```
GET /v1/loans/{loanAccountId}/payments?page=0&size=12

Cursor-based pagination (payment history is append-only, cursor is stable):
{
  "payments": [...],
  "nextCursor": "base64-encoded-cursor",
  "hasMore": false
}
```

### Request Prepayment Quote

```
GET /v1/loans/{loanAccountId}/prepayment-quote

Response 200:
{
  "currentDate": "2024-01-15",
  "outstandingPrincipal": "423145.20",
  "prepaymentFee": "4231.45",  // 1% of outstanding
  "totalPayableForClosure": "427376.65",
  "interestSavedIfClosed": "34812.30"
}
```

### Submit Prepayment

```
POST /v1/loans/{loanAccountId}/prepayments
Idempotency-Key: {client-uuid}

Body:
{
  "amount": { "amount": "50000.00", "currency": "INR" },
  "type": "PARTIAL",  // or FULL
  "paymentReference": "UPI-txn-ref-123"
}

Response 202:
{
  "prepaymentId": "uuid",
  "status": "PROCESSING",
  "message": "Payment being verified. Schedule will be updated within 2 hours."
}
```

---

## API: Back-Office (Loan Officers / Underwriters)

### Underwriter Dashboard — Pending Tasks

```
GET /v1/backoffice/tasks?status=PENDING_MAKER&assigneeId={userId}&page=0&size=20

Response 200:
{
  "tasks": [
    {
      "taskId": "uuid",
      "applicationId": "uuid",
      "applicantName": "John Doe",
      "loanAmount": "750000.00",
      "bureauScore": 712,
      "dtiRatio": 0.43,
      "createdAt": "...",
      "slaDeadline": "2024-01-16T10:00:00Z"
    }
  ],
  "totalPending": 45,
  "nextCursor": "..."
}
```

### Submit Maker Decision

```
POST /v1/backoffice/tasks/{taskId}/maker-decision
Body:
{
  "decision": "APPROVE",  // or REJECT
  "notes": "Strong income, manageable DTI",
  "conditionsIfAny": []
}

Response 200:
{
  "taskId": "uuid",
  "status": "PENDING_CHECKER",
  "checkerAssignedTo": "senior-uw-team"
}
```

### Submit Checker Decision

```
POST /v1/backoffice/tasks/{taskId}/checker-decision
Body:
{
  "decision": "APPROVE",
  "notes": "Concur with maker assessment"
}
```

---

## Error Handling

RFC 7807 Problem Details:

```json
{
  "type": "https://api.lender.com/errors/insufficient-documents",
  "title": "Required Documents Missing",
  "status": 422,
  "detail": "Salary slip for last 3 months is required. Only 1 month uploaded.",
  "applicationId": "uuid",
  "missingDocuments": ["SALARY_SLIP_MONTH_2", "SALARY_SLIP_MONTH_3"]
}
```

### Standard Error Codes

| HTTP | Error Code | Meaning |
|------|-----------|---------|
| 400 | VALIDATION_FAILED | Request schema invalid |
| 404 | APPLICATION_NOT_FOUND | Application ID not found |
| 409 | DUPLICATE_REQUEST | Idempotency key already used |
| 422 | INSUFFICIENT_DOCUMENTS | Missing required documents |
| 422 | ELIGIBILITY_FAILED | Bureau score or DTI outside policy |
| 422 | OFFER_EXPIRED | Offer validity window passed |
| 403 | UNAUTHORIZED_ACTION | Checker cannot approve own submission |
| 423 | LOAN_ACCOUNT_LOCKED | Operation blocked (e.g., NPA status) |

---

## Webhook Notifications (to Borrower Systems)

For partner integrations, system sends webhooks on key events:

```
POST https://partner-callback-url.com/loan-events

Body:
{
  "eventType": "LOAN_DISBURSED",
  "eventId": "uuid",
  "loanApplicationId": "uuid",
  "loanAccountId": "uuid",
  "disbursedAmount": "500000.00",
  "currency": "INR",
  "occurredAt": "2024-01-15T12:00:00Z"
}
```

**Delivery guarantee:** At-least-once via Kafka consumer. Idempotency key in payload for receiver deduplication. Retries: exponential backoff, 5 attempts, then DLQ + manual retry.

---

## Pagination Strategy

| Endpoint | Strategy | Reason |
|----------|---------|--------|
| Application list (back-office) | Cursor-based | Large dataset, requires stable ordering |
| Repayment history | Cursor-based | Append-only, large history |
| EMI schedule | Full result (no pagination) | Max 360 entries (30 years), manageable in one response |
| Back-office task queue | Cursor-based | Volatile dataset (tasks claimed/released) |
| Audit log query | Cursor-based | Immutable, large |

Offset pagination avoided everywhere — it is unreliable on datasets that change between pages (skips or duplicates records).
