# 14 — Interview Discussion Points: KYC / Identity Verification Pipeline

---

## Objective

Prepare for in-depth interviewer challenges on the KYC design. Covers regulatory concepts, state machine design, vendor management, and staff-level architectural thinking.

---

## Fundamental Concepts

### Q1: What is KYC and why does it matter technically?

KYC is the process of verifying a customer's identity before they can use financial services. Technically, it means:
- Accepting identity documents (Aadhaar, PAN, Passport)
- Extracting and validating the data (OCR)
- Confirming the person is real and present (liveness check)
- Checking they are not on government watchlists (sanctions, PEP lists)
- Maintaining an immutable audit trail of all verification steps

Non-compliance consequences: financial fines (HDFC Bank was fined ₹10 crore for KYC deficiencies in 2023), license suspension, and criminal liability for senior management.

### Q2: What is the difference between C-KYC (Central KYC) and traditional KYC?

C-KYC (India): A centralized repository maintained by CERSAI. A customer verified once at any financial institution gets a 14-digit KYC ID. Other institutions can use this ID to complete KYC without re-verifying from scratch.

**Engineering impact:** If the user provides a C-KYC ID, the KYC pipeline calls the C-KYC registry API to fetch the verified data — OCR step is replaced with a registry lookup. This is modeled as a `CKYC_FETCH` verification step in the vendor abstraction layer.

### Q3: Walk me through what happens when a user submits their Aadhaar card.

1. Frontend requests a presigned S3 URL from the KYC service
2. User uploads Aadhaar front + back images directly to S3 (never passes through backend)
3. Onboarding service calls `POST /kyc/applications` with the S3 document keys + personal details
4. KYC service stores the application (personal data encrypted with KMS)
5. Async pipeline triggers: fetch document from S3, call DigiLocker API for Aadhaar verification
6. DigiLocker returns: verified name, DOB, masked Aadhaar number, photo (if eKYC with OTP)
7. Pipeline compares submitted name/DOB with extracted name/DOB — must match within fuzzy threshold
8. If match: DOCUMENT_VERIFIED. If mismatch: DOCUMENT_REJECTED or MANUAL_REVIEW
9. Pipeline triggers liveness check: user's selfie verified against Aadhaar photo
10. Watchlist screening: extracted name + DOB + nationality screened against sanctions lists
11. If all pass: APPROVED; Kafka event triggers Onboarding to activate the account

---

## Common Interview Challenges

### Challenge 1: "Your state machine looks good, but how do you ensure consistency across service restarts?"

State persistence is in PostgreSQL — not in-memory. On service restart:
1. Pipeline workers consume Kafka `kyc.application.submitted` and `kyc.step.completed` events from the last committed offset
2. A stale step detector runs on startup: queries for steps in IN_PROGRESS status for > 30 minutes (max vendor timeout) → resets to PENDING and re-emits pending events
3. The state machine only accepts valid transitions from the current DB state — replay of old Kafka events does not cause incorrect transitions (idempotent state machine)

---

### Challenge 2: "How do you prevent a vendor from seeing your customers' document images?"

Two-step approach:
1. **Presigned URL upload:** Documents go directly from user device to S3 — never pass through the KYC backend
2. **Selective disclosure to vendor:** The pipeline decrypts the document in memory (never writes to disk), sends only the relevant bytes to the vendor API over HTTPS, and immediately discards the plaintext from memory
3. **Data processing agreements (DPAs):** Contractual obligation — vendors are prohibited from retaining document data beyond the API call. Audited annually

For high-security scenarios: the vendor API is called within a VPC private endpoint (S3 VPC Endpoint equivalent for vendor APIs if available) — document bytes never traverse the public internet.

---

### Challenge 3: "Your manual review queue can overflow. How do you scale human review?"

Human review cannot be auto-scaled with HPA. Design for SLA management:

**Short-term (campaign day):**
- Pre-scheduled additional reviewers (compliance vendor staffing)
- Auto-clear false positives: re-run watchlist with updated threshold; if now below threshold → auto-approve with audit note

**Medium-term (ongoing):**
- Risk-based review: only EXACT matches (score > 0.95) go to MANUAL_REVIEW. FUZZY matches go to a monitoring queue — approved automatically, flagged for retroactive review within 30 days
- Machine learning triage: train a model on historical manual review decisions — auto-approve cases where officer approved similar profiles > 95% of the time (with compliance officer override)

**Long-term:**
- Video KYC for borderline cases: video call between compliance officer and customer — faster than document-only review
- Certified Third Party Intermediary (CTPI): outsource review to a regulated compliance services firm

---

### Challenge 4: "What happens if a customer is added to a watchlist AFTER they've been approved?"

This is the continuous monitoring requirement. Two mechanisms:

**Event-driven re-screening:**
- The watchlist data feed provider (LexisNexis) sends change events when their list updates
- A `WatchlistUpdateConsumer` receives the event, extracts new entries, and checks all APPROVED customers against the new entries
- If a match is found: trigger re-verification + temporary account freeze + compliance team alert

**Periodic batch re-screening:**
- Monthly: re-run all APPROVED customers through the watchlist screening step
- High-risk customers: weekly
- This is the periodic review requirement in RBI KYC Master Direction

**Technical implementation:**
- `re_verification_trigger` event with reason `PERIODIC_WATCHLIST_REVIEW`
- Creates a new `VerificationStep` of type WATCHLIST_SCREENING on the existing application
- Does not create a new `KycApplication` (only re-screening, not full re-KYC)
- If match found: account frozen, MANUAL_REVIEW triggered, customer notified per compliance protocol

---

## Senior vs Staff Engineer Depth

### Senior Engineer Level

- Knows the KYC state machine states and why they're sequential
- Can design the vendor abstraction interface
- Understands why async pipeline (not synchronous call chain)
- Knows why personal data is encrypted in the DB
- Can discuss idempotency for submission

### Staff Engineer Level

- Raises C-KYC integration as an optimization (reduce vendor costs)
- Discusses risk-based KYC tiers and their regulatory basis (FATF risk-based approach)
- Proposes biometric deduplication to prevent multi-identity fraud
- Designs continuous monitoring strategy (periodic vs event-driven re-screening)
- Discusses data residency challenges for cross-jurisdiction KYC
- Raises the "what happens after we're acquired?" scenario — how to migrate KYC records between systems while maintaining regulatory continuity

### Principal Engineer Level

- Designs the cross-jurisdiction KYC framework: different document sets, different regulatory rules, different vendors per jurisdiction
- Proposes Self-Sovereign Identity (SSI / W3C DIDs) as a future direction — user controls their verified credential, reduces vendor dependency
- Discusses the EU AI Act implications for liveness check AI (high-risk AI system classification)
- Designs the audit trail archival system that satisfies both GDPR (right to erasure) and financial regulation (7-year retention) — the crypto-erasure approach

---

## Common Mistakes Candidates Make

| Mistake | Why Wrong | Correct Approach |
|---|---|---|
| Synchronous vendor call chain | Blocks thread for 8+ seconds; no retry | Async Kafka-driven pipeline |
| Storing PII in plaintext | GDPR/DPDP violation | AES-256-GCM with KMS key management |
| Deleteable state transitions | Cannot meet audit trail requirement | Append-only state_transitions table |
| Allowing state machine skips | Regulatory requirement: no skipped steps | State machine validation before any transition |
| Vendor-specific logic in business layer | Cannot swap vendors | VendorAdapter ACL |
| One application re-attempted indefinitely | Vendor errors loop forever | Max retry policy + DLQ + MANUAL_REVIEW routing |
| PII in Kafka events | Kafka is a many-consumer system; PII exposure | Only identifiers (application_id, user_id) in events |

---

## What Would Break First Analysis

| Load Level | First Failure |
|---|---|
| 5x normal (50K/day) | Manual review queue depth — human throughput is the bottleneck |
| 10x normal (100K/day) | Vendor API rate limits (Onfido 50 RPS) |
| 20x normal (200K/day) | PostgreSQL write throughput for state transitions |
| 100x normal (1M/day) | Need separate OCR/liveness workers, read replicas for status queries |

---

## What a Senior Interviewer Will Challenge

| Challenge | Expected Response |
|---|---|
| "Can your system approve a customer that wasn't watchlist-screened?" | No — state machine enforces WATCHLIST_CLEAR or MANUAL_REVIEW before APPROVED. Compliance test suite verifies this invariant |
| "How do you handle Aadhaar OTP authentication (a different flow from document upload)?" | The vendor abstraction layer handles this: `DigiLockerOtpVendorAdapter.performDocumentOCR(aadhaarNumber, otpToken)` → different input, same OcrResult output |
| "Your PII encryption adds ~50ms latency per application. Is that acceptable?" | Yes — KYC takes 5–10 minutes total; 50ms is 0.1% of total time. The KMS DEK cache reduces this to ~5ms for most calls after the first. Use `CachingMaterialsProvider` in the AWS Encryption SDK |
| "How do you handle the right to access (DPDP Article 11) — user wants their KYC data?" | Expose an authenticated endpoint for the user (via Onboarding service): `GET /users/{id}/kyc-data`. Returns decrypted personal_data and state transition history. All access logged to audit trail |
| "How do you test your liveness check against deepfakes?" | (1) Vendor responsibility — select vendors that pass the ISO 30107-3 PAD (Presentation Attack Detection) test, (2) Internal: quarterly penetration test by a third party using state-of-the-art deepfake tools, (3) Monitor liveness failure rate — spike may indicate a new attack technique in the wild |
