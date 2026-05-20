# 04 — API Design: KYC / Identity Verification Pipeline

---

## Objective

Define the REST API surface for KYC application submission, status queries, manual review, and document upload. Address idempotency, async response patterns, and regulatory data exposure controls.

---

## API Style Decision

**External-facing (Onboarding Service):** REST/JSON — human-readable, compatible with all clients.

**Why not gRPC here?** KYC submission is not high-frequency (10,000/day). REST is sufficient and easier to integrate with the onboarding web flow and mobile clients. gRPC is reserved for latency-critical internal paths (the ledger's balance reads at 50K RPS). KYC submission at < 1 RPS average does not justify binary protocol overhead.

---

## Versioning Strategy

- URL path versioning: `/api/v1/kyc`, `/api/v2/kyc`
- Minimum 6-month deprecation window before removing a version
- `Deprecation` and `Sunset` headers in responses for deprecated endpoints
- Kafka event schemas versioned via Confluent Schema Registry (BACKWARD compatibility)

---

## API Endpoints

### 1. Document Upload (Presigned URL Flow)

Documents are uploaded directly to S3 by the client — never through the KYC service. This keeps the KYC service outside PCI/PII scope for raw document bytes.

#### `POST /api/v1/kyc/uploads/presigned-url`

Request a presigned S3 URL for document upload.

**Request:**
```json
{
  "document_type": "AADHAAR",
  "side": "FRONT",
  "file_size_bytes": 1048576,
  "content_type": "image/jpeg"
}
```

**Response `200 OK`:**
```json
{
  "upload_url": "https://s3.amazonaws.com/kyc-docs/temp/...",
  "document_key": "temp/usr_abc123/doc_uuid_front.jpg",
  "expires_in_seconds": 300,
  "max_file_size_bytes": 5242880
}
```

Client uploads to `upload_url` directly. The S3 key (`document_key`) is included in the KYC application submission.

**Security on the S3 side:**
- Presigned URL scoped to one specific key — client cannot overwrite other documents
- Content-Type enforced: only `image/jpeg`, `image/png`, `application/pdf` allowed
- S3 bucket is private — presigned URL is the only access path
- Documents moved from `temp/` to `kyc-docs/` encrypted prefix after verification (Lambda trigger on upload)

---

### 2. KYC Application Submission

#### `POST /api/v1/kyc/applications`

Submit a KYC application. Idempotent by `idempotency_key`.

**Request:**
```json
{
  "idempotency_key": "onboarding:usr_abc123:kyc_attempt_1",
  "user_id": "usr_abc123",
  "kyc_tier": "STANDARD",
  "personal_data": {
    "full_name": "Piyush Prasad",
    "date_of_birth": "1995-06-15",
    "nationality": "IN",
    "address": {
      "line1": "123 Main Street",
      "city": "Mumbai",
      "state": "MH",
      "pin_code": "400001"
    }
  },
  "documents": [
    {
      "document_type": "AADHAAR",
      "side": "FRONT",
      "document_key": "temp/usr_abc123/doc_uuid_front.jpg"
    },
    {
      "document_type": "AADHAAR",
      "side": "BACK",
      "document_key": "temp/usr_abc123/doc_uuid_back.jpg"
    },
    {
      "document_type": "SELFIE",
      "side": "FRONT",
      "document_key": "temp/usr_abc123/selfie_uuid.jpg"
    }
  ]
}
```

**Response `202 Accepted`:**
```json
{
  "application_id": "kyc_app_uuid",
  "status": "SUBMITTED",
  "kyc_tier": "STANDARD",
  "estimated_completion_seconds": 180,
  "submitted_at": "2024-01-15T10:30:00Z",
  "status_url": "/api/v1/kyc/applications/kyc_app_uuid"
}
```

**`202 Accepted`** — not 200 or 201. The application is accepted but the verification is not yet complete. The caller must poll `status_url` or subscribe to Kafka events.

**Idempotent repeat:** Same `idempotency_key` returns `200 OK` with existing application state.

---

### 3. Application Status Query

#### `GET /api/v1/kyc/applications/{application_id}`

Poll application status. No PII in the response — only status and step outcomes.

**Response `200 OK` (in-progress):**
```json
{
  "application_id": "kyc_app_uuid",
  "user_id": "usr_abc123",
  "status": "LIVENESS_PENDING",
  "kyc_tier": "STANDARD",
  "submitted_at": "2024-01-15T10:30:00Z",
  "steps": [
    {
      "step_type": "DOCUMENT_OCR",
      "status": "PASS",
      "completed_at": "2024-01-15T10:30:15Z"
    },
    {
      "step_type": "LIVENESS",
      "status": "IN_PROGRESS",
      "started_at": "2024-01-15T10:30:16Z"
    }
  ],
  "estimated_completion_seconds": 120
}
```

**Response `200 OK` (completed):**
```json
{
  "application_id": "kyc_app_uuid",
  "status": "APPROVED",
  "decided_at": "2024-01-15T10:32:45Z",
  "decision_source": "AUTOMATED"
}
```

**Response `200 OK` (rejected):**
```json
{
  "application_id": "kyc_app_uuid",
  "status": "REJECTED",
  "decided_at": "2024-01-15T10:33:00Z",
  "rejection_reason": "DOCUMENT_EXPIRED",
  "rejection_category": "DOCUMENT_ISSUE"
}
```

`rejection_reason` is a code (not free text) — allows frontend to display localized, user-friendly messages. Free-text rejection reasons risk exposing internal system details.

---

### 4. State Transition History (Audit)

#### `GET /api/v1/kyc/applications/{application_id}/transitions`

Return the full state transition history. Requires elevated `kyc:read_audit` scope.

**Response `200 OK`:**
```json
{
  "application_id": "kyc_app_uuid",
  "transitions": [
    {
      "from_status": null,
      "to_status": "SUBMITTED",
      "trigger": "API",
      "triggered_by": "onboarding-service",
      "reason": "Application submitted",
      "occurred_at": "2024-01-15T10:30:00Z"
    },
    {
      "from_status": "SUBMITTED",
      "to_status": "DOCUMENT_VERIFICATION_PENDING",
      "trigger": "SYSTEM",
      "triggered_by": "kyc-pipeline",
      "reason": "Documents validated, starting OCR",
      "occurred_at": "2024-01-15T10:30:02Z"
    }
  ]
}
```

---

### 5. Manual Review Endpoints

#### `GET /api/v1/kyc/review/queue`

List applications pending manual review. Requires `kyc:review` scope (compliance officers).

Query params: `?assigned_to={reviewer_id}&priority={HIGH|MEDIUM|LOW}&limit=20&cursor={cursor}`

**Response:**
```json
{
  "items": [
    {
      "application_id": "kyc_app_uuid",
      "submitted_at": "2024-01-15T08:00:00Z",
      "routing_reason": "WATCHLIST_HIT",
      "priority": "HIGH",
      "age_minutes": 120
    }
  ],
  "next_cursor": "abc123"
}
```

Note: No PII in this listing. Reviewer clicks into an application to request PII access (logged event).

#### `POST /api/v1/kyc/review/{application_id}/decision`

Submit manual review decision. Requires `kyc:review` scope.

**Request:**
```json
{
  "decision": "APPROVED",
  "notes": "Watchlist hit is a name similarity match, not a confirmed PEP. Document verified in person.",
  "reviewer_id": "officer_xyz"
}
```

**Response `200 OK`:**
```json
{
  "application_id": "kyc_app_uuid",
  "new_status": "APPROVED",
  "decided_by": "officer_xyz",
  "decided_at": "2024-01-15T11:00:00Z"
}
```

---

### 6. Re-Verification Trigger

#### `POST /api/v1/kyc/applications/{application_id}/re-verify`

Trigger a new KYC round for an already-approved customer.

**Request:**
```json
{
  "idempotency_key": "re-kyc:usr_abc123:trigger_regulatory_change_2024",
  "trigger_reason": "REGULATORY_CHANGE",
  "kyc_tier": "FULL",
  "initiated_by": "compliance-service"
}
```

**Response `202 Accepted`:**
```json
{
  "new_application_id": "kyc_app_new_uuid",
  "parent_application_id": "kyc_app_original_uuid",
  "status": "SUBMITTED"
}
```

---

## Idempotency Design

| Operation | Idempotency Key | Storage | Behavior on Repeat |
|---|---|---|---|
| Submit application | `idempotency_key` in body | Redis (24h) + DB UNIQUE | Return existing application |
| Manual review decision | `{application_id}+{reviewer_id}+{decision}` | DB UNIQUE on review_id | 409 Conflict if already decided |
| Re-verification trigger | `idempotency_key` in body | Redis + DB UNIQUE | Return existing re-verification application |

---

## Error Codes

| HTTP Status | Error Code | Description |
|---|---|---|
| `400` | `INVALID_DOCUMENT_TYPE` | Unsupported document type for the requested KYC tier |
| `400` | `DOCUMENT_KEY_EXPIRED` | Presigned URL or temp S3 key has expired |
| `400` | `INVALID_KYC_TIER` | Requested KYC tier not supported for user's jurisdiction |
| `404` | `APPLICATION_NOT_FOUND` | application_id not found |
| `409` | `APPLICATION_ALREADY_DECIDED` | Attempting to submit decision on APPROVED/REJECTED application |
| `409` | `ACTIVE_APPLICATION_EXISTS` | User already has an in-progress KYC application |
| `422` | `PERSONAL_DATA_MISMATCH` | Submitted name/DOB does not match extracted OCR data (catch in Submit) |
| `429` | `RATE_LIMIT_EXCEEDED` | Too many submissions for this user |
| `503` | `VENDOR_UNAVAILABLE` | All OCR/liveness vendors are down |

---

## PII Exposure Controls in API Responses

| Endpoint | Caller Scope Required | PII Exposed |
|---|---|---|
| GET /applications/{id} | `kyc:read_status` | None — status only |
| GET /applications/{id}/transitions | `kyc:read_audit` | None — metadata only |
| GET /review/queue | `kyc:review` | None — IDs and age only |
| GET /review/{id}/pii | `kyc:review_pii` + access logged | Full PII for reviewer |
| GET /applications/{id}/ocr_result | `kyc:read_extraction` | Extracted fields (not image) |

**PII access is logged:** Every call to any endpoint that returns personal data generates an audit log entry. Reviewers cannot access PII without creating an audit trail.

---

## Webhook Support (Vendor Callbacks)

Vendors like Onfido send results via webhook. The KYC service exposes:

#### `POST /api/v1/kyc/webhooks/{vendor_id}`

**Security:**
- Endpoint validates HMAC-SHA256 signature in `X-Signature` header
- Signature key rotated quarterly per vendor
- Requests without valid signature → 401 Unauthorized + security alert logged
- Webhook payload stored raw for audit before processing

**Processing:**
- Webhook accepted with 200 OK immediately (< 100ms)
- Payload queued for async processing (do not block on webhook response)
- Idempotent: `vendor_reference_id` deduplicated in Redis — duplicate webhooks ignored

---

## Interview Discussion Points

- **Why 202 Accepted instead of 200 OK for application submission?** The work has not completed. 200 OK implies the request was fulfilled. 202 Accepted means "received and will process." This is the correct semantics for an async pipeline. The client should not assume KYC is done just because submission succeeded
- **How does the mobile client know when KYC is complete?** Three options: (1) polling `GET /applications/{id}` every 5 seconds (simple but wasteful), (2) Server-Sent Events (SSE) — client opens a long-lived connection, server pushes state changes, (3) push notification — KYC service publishes to Notification Service which sends FCM push to mobile. Production systems use SSE or push; polling is MVP
- **Why not return PII in the status endpoint?** Principle of least privilege and data minimization. The upstream service (Onboarding) does not need to see the extracted OCR data — it only needs to know if KYC passed or failed. PII is returned only to authorized reviewers via a separate endpoint with access logging
- **How do you handle the case where a user submits multiple KYC applications simultaneously?** `ACTIVE_APPLICATION_EXISTS` error if a non-terminal application exists for the same user. Only one in-flight application per user at a time. Enforced by a UNIQUE constraint on `(user_id, status NOT IN ('APPROVED', 'REJECTED'))` — PostgreSQL partial unique index
