# 06 — Event Flow: KYC / Identity Verification Pipeline

---

## Objective

Document the complete event flows for KYC application processing, vendor callbacks, manual review, and downstream event consumption. The pipeline is async — events drive step transitions.

---

## Event Flows Overview

| Flow | Trigger | Duration |
|---|---|---|
| 1. Automated KYC (happy path) | Application submitted | 2–5 minutes |
| 2. Vendor callback (webhook) | Vendor posts result | < 2 seconds |
| 3. Manual review routing | Watchlist hit or quality issue | < 5 minutes to route; up to 24h for decision |
| 4. Re-verification trigger | Regulatory or risk event | Same as Flow 1 |
| 5. PII purge | Scheduled or user request | Background job |
| 6. Vendor fallback | Primary vendor unavailable | + 1–2 minutes |

---

## Flow 1: Automated KYC (Happy Path)

```mermaid
sequenceDiagram
    participant ONBOARD as Onboarding Service
    participant API as KYC API
    participant SM as State Machine
    participant PIPELINE as Pipeline Orchestrator
    participant S3 as S3
    participant OCR_VENDOR as DigiLocker/Onfido
    participant LIVE_VENDOR as Onfido Liveness
    participant WATCH_VENDOR as LexisNexis
    participant DB as PostgreSQL
    participant KAFKA as Kafka

    ONBOARD->>API: POST /kyc/applications {user_id, docs, personal_data}
    API->>DB: BEGIN TX
    API->>DB: INSERT kyc_application (status=SUBMITTED)
    API->>DB: INSERT state_transition (→SUBMITTED)
    API->>DB: COMMIT
    API-->>ONBOARD: 202 Accepted {application_id}

    Note over PIPELINE: Async — triggered by SUBMITTED event on Kafka

    PIPELINE->>DB: UPDATE step DOCUMENT_OCR: status=IN_PROGRESS
    PIPELINE->>DB: INSERT state_transition (→DOCUMENT_VERIFICATION_PENDING)
    PIPELINE->>S3: Fetch encrypted document bytes
    S3-->>PIPELINE: encrypted document
    PIPELINE->>PIPELINE: Decrypt with KMS, prepare for vendor

    par OCR Step
        PIPELINE->>OCR_VENDOR: POST /extractions {document_bytes}
        OCR_VENDOR-->>PIPELINE: {name, dob, id_number, confidence: 0.98}
    end

    PIPELINE->>DB: BEGIN TX
    PIPELINE->>DB: UPDATE verification_step DOCUMENT_OCR: status=PASS, result={...}
    PIPELINE->>DB: INSERT state_transition (→DOCUMENT_VERIFIED)
    PIPELINE->>DB: COMMIT

    PIPELINE->>PIPELINE: Determine next step (LIVENESS based on tier=STANDARD)
    PIPELINE->>DB: UPDATE step LIVENESS: status=IN_PROGRESS
    PIPELINE->>LIVE_VENDOR: POST /liveness {selfie_bytes}
    LIVE_VENDOR-->>PIPELINE: {is_live: true, confidence: 0.99}

    PIPELINE->>DB: BEGIN TX
    PIPELINE->>DB: UPDATE verification_step LIVENESS: status=PASS
    PIPELINE->>DB: INSERT state_transition (→LIVENESS_PASSED)
    PIPELINE->>DB: COMMIT

    PIPELINE->>PIPELINE: Determine next step (WATCHLIST based on tier=STANDARD)
    PIPELINE->>WATCH_VENDOR: POST /screen {name, dob, nationality}
    WATCH_VENDOR-->>PIPELINE: {hits: [], risk_level: LOW}

    PIPELINE->>DB: BEGIN TX
    PIPELINE->>DB: UPDATE verification_step WATCHLIST: status=PASS
    PIPELINE->>DB: INSERT state_transition (→WATCHLIST_CLEAR)
    PIPELINE->>DB: INSERT state_transition (→APPROVED)
    PIPELINE->>DB: UPDATE kyc_application SET status=APPROVED, approved_at=now()
    PIPELINE->>DB: COMMIT

    PIPELINE->>KAFKA: Produce kyc.outcome.decided {application_id, outcome=APPROVED}
    KAFKA->>ONBOARD: Consume kyc.outcome.decided → activate user account
```

**Total time:** Document fetch (1s) + OCR (2s) + Liveness (3s) + Watchlist (0.5s) + DB operations (0.3s) ≈ **~7 seconds**. Well within the 3-minute target.

---

## Flow 2: Vendor Webhook Callback (Async Vendor)

Some vendors (Onfido) do not return results synchronously — they process and call back via webhook.

```mermaid
sequenceDiagram
    participant PIPELINE as Pipeline Orchestrator
    participant ONFIDO as Onfido
    participant WEBHOOK as Webhook Endpoint
    participant QUEUE as Processing Queue
    participant DB as PostgreSQL

    PIPELINE->>ONFIDO: POST /v3.6/checks {applicant_id, report_names: ["document"]}
    ONFIDO-->>PIPELINE: 201 Created {check_id: "chk_abc123"}

    Note over DB: Store vendor_reference_id=chk_abc123 in verification_step

    PIPELINE->>DB: UPDATE verification_step: vendor_reference_id=chk_abc123, status=IN_PROGRESS

    Note over ONFIDO: Onfido processes asynchronously...

    ONFIDO->>WEBHOOK: POST /webhooks/onfido {check_id, status=complete, result=clear}
    WEBHOOK->>WEBHOOK: Validate HMAC-SHA256 signature
    WEBHOOK->>DB: INSERT webhook_events (raw_payload, vendor=ONFIDO)
    WEBHOOK-->>ONFIDO: 200 OK (immediately)

    WEBHOOK->>QUEUE: Enqueue processing task {webhook_id}

    QUEUE->>DB: SELECT webhook_event WHERE id = webhook_id
    QUEUE->>DB: SELECT verification_step WHERE vendor=ONFIDO AND vendor_reference_id=chk_abc123
    QUEUE->>QUEUE: Map Onfido result → OcrResult domain object
    QUEUE->>DB: UPDATE verification_step: status=PASS, result={...}
    QUEUE->>DB: INSERT state_transition (→DOCUMENT_VERIFIED)
    QUEUE->>DB: UPDATE webhook_events SET processed=true
    QUEUE->>PIPELINE: Trigger next step
```

**Key pattern:** The webhook endpoint MUST return 200 within 3 seconds (Onfido's timeout). It stores the payload and queues processing. Never do business logic synchronously in the webhook handler.

---

## Flow 3: Manual Review Routing (Watchlist Hit)

```mermaid
sequenceDiagram
    participant PIPELINE as Pipeline Orchestrator
    participant DB as PostgreSQL
    participant KAFKA as Kafka
    participant DASHBOARD as Review Dashboard
    participant OFFICER as Compliance Officer

    PIPELINE->>WATCH_VENDOR: Screen {name, dob, nationality}
    WATCH_VENDOR-->>PIPELINE: {hits: [{list: "PEP", match_score: 0.87}], risk_level: HIGH}

    PIPELINE->>DB: BEGIN TX
    PIPELINE->>DB: UPDATE verification_step WATCHLIST: status=MANUAL
    PIPELINE->>DB: INSERT state_transition (→WATCHLIST_HIT)
    PIPELINE->>DB: INSERT state_transition (→MANUAL_REVIEW)
    PIPELINE->>DB: UPDATE kyc_application SET status=MANUAL_REVIEW
    PIPELINE->>DB: INSERT manual_review_queue {priority=HIGH, routing_reason=WATCHLIST_HIT}
    PIPELINE->>DB: COMMIT

    PIPELINE->>KAFKA: Produce kyc.manual_review.required {application_id, routing_reason, priority}

    KAFKA->>DASHBOARD: Consume → update review queue display (real-time)

    Note over OFFICER: Compliance officer reviews queue

    OFFICER->>DASHBOARD: Open application {application_id}
    DASHBOARD->>API: GET /kyc/review/{id}/pii (access logged)
    API-->>DASHBOARD: Full PII (decrypted, access audit event fired)

    OFFICER->>DASHBOARD: Submit decision {APPROVED, notes}
    DASHBOARD->>API: POST /kyc/review/{id}/decision {decision=APPROVED}

    API->>DB: BEGIN TX
    API->>DB: UPDATE manual_review_queue: decision=APPROVED, completed_at=now()
    API->>DB: UPDATE verification_step MANUAL_REVIEW: status=PASS
    API->>DB: INSERT state_transition (→APPROVED)
    API->>DB: UPDATE kyc_application SET status=APPROVED, approved_at=now()
    API->>DB: COMMIT

    API->>KAFKA: Produce kyc.outcome.decided {outcome=APPROVED, decision_source=OPERATOR}
```

---

## Flow 4: Vendor Fallback (Primary Vendor Unavailable)

```mermaid
sequenceDiagram
    participant PIPELINE as Pipeline Orchestrator
    participant ROUTER as VendorRouter
    participant ONFIDO as Onfido (primary)
    participant JUMIO as Jumio (fallback)
    participant CIRCUIT as Circuit Breaker
    participant DB as PostgreSQL

    PIPELINE->>ROUTER: selectVendor(DOCUMENT_OCR, AADHAAR, IN)
    ROUTER-->>PIPELINE: Onfido (primary for AADHAAR)

    PIPELINE->>CIRCUIT: check(Onfido) → CLOSED (healthy)
    PIPELINE->>ONFIDO: POST /extractions {document}
    ONFIDO-->>PIPELINE: HTTP 503 (Onfido outage)

    PIPELINE->>CIRCUIT: recordFailure(Onfido)
    Note over CIRCUIT: After 5 failures in 60s, circuit OPENS

    PIPELINE->>ROUTER: getFallbackVendor(Onfido, DOCUMENT_OCR)
    ROUTER-->>PIPELINE: Jumio (fallback)

    PIPELINE->>DB: UPDATE verification_step: retry_count=1, vendor=JUMIO
    PIPELINE->>JUMIO: POST /scans {document}
    JUMIO-->>PIPELINE: 200 OK {result: "approved", extracted_data: {...}}

    PIPELINE->>PIPELINE: Map Jumio response → OcrResult (via JumioVendorAdapter)
    PIPELINE->>DB: UPDATE verification_step: status=PASS, result={...}
    PIPELINE->>DB: INSERT state_transition (→DOCUMENT_VERIFIED)

    Note over CIRCUIT: Onfido circuit reopens after 60s half-open probe
```

---

## Flow 5: PII Purge (Scheduled)

```mermaid
sequenceDiagram
    participant SCHEDULER as @Scheduled (2 AM daily)
    participant PURGE as PiiPurgeService
    participant DB as PostgreSQL
    participant KMS as AWS KMS
    participant S3 as S3
    participant AUDIT as Audit Log

    SCHEDULER->>PURGE: runPurge()
    PURGE->>DB: SELECT application_id, personal_data_key_version FROM kyc_applications WHERE pii_expires_at <= now() AND is_pii_purged = false LIMIT 1000
    DB-->>PURGE: [batch of applications]

    loop for each application
        PURGE->>KMS: ScheduleKeyDeletion(key_version, pending_window=7_days)
        PURGE->>S3: DeleteObject (each document_reference s3_key)
        PURGE->>DB: BEGIN TX
        PURGE->>DB: UPDATE kyc_applications SET personal_data_encrypted=null, is_pii_purged=true, pii_purged_at=now()
        PURGE->>DB: UPDATE document_references SET is_purged=true, purged_at=now()
        PURGE->>DB: COMMIT
        PURGE->>AUDIT: Log purge event {application_id, purged_at, trigger}
    end
```

---

## Kafka Event Schema Catalog

| Topic | Key | Retention | Payload Size |
|---|---|---|---|
| `kyc.application.submitted` | application_id | 7 days | < 1 KB (no PII) |
| `kyc.step.completed` | application_id | 7 days | < 2 KB (result metadata, no raw data) |
| `kyc.manual_review.required` | application_id | 30 days | < 1 KB |
| `kyc.outcome.decided` | application_id | 90 days | < 1 KB |
| `kyc.application.expired` | application_id | 7 days | < 500 bytes |

**No PII in Kafka events.** All events reference `application_id` and `user_id` as opaque UUIDs. No names, document numbers, or extracted data in the message payload.

---

## Failure Paths in Event Flow

| Failure | Detection | Recovery |
|---|---|---|
| Vendor OCR timeout | Timeout after 10s, retry circuit | Retry with same/fallback vendor; escalate to manual after 3 retries |
| Webhook signature invalid | 401 returned, security alert | Drop the event, security team investigates |
| DB transaction fails mid-transition | Rollback; state unchanged | Pipeline retries the step from current state |
| Kafka publish fails for outcome | Outbox pattern: retry until Kafka available | Onboarding polls status as fallback |
| Manual review not assigned for > 24h | Scheduled check for queue age | Auto-escalate priority; page compliance manager |

---

## Interview Discussion Points

- **Why not use a workflow engine (Temporal, Camunda) for the pipeline orchestration?** A custom state machine in PostgreSQL + Kafka is sufficient for 10,000 applications/day with 5–8 steps. Temporal adds value when workflows run for days or weeks (like loan servicing). KYC typically completes in minutes. The complexity cost of Temporal is not justified at this scale
- **What happens if the pipeline service restarts mid-processing?** The state machine is persisted in PostgreSQL. On restart, the pipeline queries for applications in non-terminal states that have been updated > 5 minutes ago (stalled) and re-triggers the current pending step. This is the "resumable pipeline" guarantee
- **How do you ensure exactly-once state transitions?** Each transition is written in a PostgreSQL transaction that also updates the application status. The UNIQUE index on `(application_id, step_type)` prevents duplicate steps. The state machine validates that the requested transition is valid from the current state — an already-completed step cannot be re-run
