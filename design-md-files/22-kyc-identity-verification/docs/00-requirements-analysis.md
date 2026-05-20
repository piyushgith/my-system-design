# 00 — Requirements Analysis: KYC / Identity Verification Pipeline

---

## Objective

Define the functional and non-functional requirements, constraints, assumptions, and capacity estimates for a production-grade KYC (Know Your Customer) identity verification pipeline used in financial onboarding.

---

## Functional Requirements

### Core (MVP)

- Accept an identity verification application from a user (personal details, document upload)
- Extract and validate document data (OCR on Aadhaar, PAN, Passport, Driver's License)
- Run liveness check to confirm the user is a real person (not a photo)
- Screen against government sanctions and PEP (Politically Exposed Person) watchlists
- Track KYC application state machine: SUBMITTED → DOCUMENT_VERIFIED → LIVENESS_PASSED → WATCHLIST_CLEAR → APPROVED / REJECTED
- Support manual review queue for cases that automated systems cannot resolve
- Notify the upstream onboarding service of the final KYC outcome

### Extended (V1)

- Support multiple KYC tiers: Basic (Aadhaar OTP only) and Full (document + liveness + watchlist)
- Vendor abstraction layer: swap between Onfido, Jumio, DigiLocker without changing the KYC service API
- Re-verification triggers: re-run KYC when user data changes (new address, name change) or on regulatory mandate
- Data retention and purge: PII retained only as long as required by regulation (2 years post-closure for most jurisdictions)
- Audit trail for regulators: every state transition recorded with timestamp, operator, and reason

### Advanced (V2+)

- Risk-based KYC (RBA): dynamically select KYC tier based on transaction risk profile and user segment
- Video KYC for premium users (agent-assisted identity verification via video call)
- Biometric enrollment: store encrypted facial embedding for future authentication challenges
- Cross-jurisdiction KYC: support different document sets and regulatory rules per country
- Continuous monitoring: re-screen existing customers against updated watchlists periodically

---

## Non-Functional Requirements

| Property | Target |
|---|---|
| Availability | 99.9% — onboarding is critical path for new user revenue |
| KYC completion time (automated path) | < 3 minutes end-to-end (document upload → approved) |
| Manual review SLA | < 24 hours |
| Document OCR accuracy | > 98% (reject below threshold, route to manual) |
| State machine correctness | Exactly-once state transitions (no skipped or duplicated steps) |
| PII data security | Encrypted at rest and in transit; access-logged; purged per retention policy |
| Throughput | 10,000 KYC applications per day (average), 50,000 peak (campaign days) |
| Audit | Every state transition immutable, traceable to operator |
| GDPR/DPDP compliance | Right to erasure: PII purged on user request post-closure |

---

## Assumptions

- The service is internal — called by the onboarding flow, not exposed directly to end users
- Document images are uploaded directly to S3 by the client (presigned URL pattern) — the KYC service receives only the S3 reference, not the raw bytes
- External verification vendors (Onfido, DigiLocker) are accessed via HTTP APIs — the KYC service is the client, not a callback receiver in the primary flow
- KYC outcome (APPROVED/REJECTED/MANUAL_REVIEW) is communicated back to the onboarding service via Kafka event
- Facial embeddings for biometric enrollment are stored in a separate secure vault (out of scope for MVP)
- Watchlist data is provided by a third-party data feed (Dow Jones, LexisNexis Risk Solutions) ingested periodically

---

## Constraints

- PII (document images, facial scan, name, DOB, government ID numbers) must be encrypted using AES-256-GCM at rest
- Document images must never be stored unencrypted — even in transit between internal services
- No KYC state transition can be reversed — rejections are final per regulatory requirement (a new application must be submitted)
- Manual review operators can only see pseudonymized applicant data until they explicitly request full data access (logged)
- The KYC service must not retain document images beyond the regulatory retention period (2 years after customer closure or 5 years after transaction, whichever is longer — varies by jurisdiction)
- Vendor API keys must be stored in a secrets manager, never in application configuration files

---

## Scale Estimation

### Traffic Assumptions

| Metric | Value |
|---|---|
| KYC applications per day (avg) | 10,000 |
| KYC applications per day (peak) | 50,000 |
| Documents per application | 2 (ID front, ID back + optional selfie) |
| Document size | 1–3 MB per image |
| Vendor API calls per application | 3–5 (OCR, liveness, watchlist, potentially 2 watchlist providers) |
| Manual review rate | 10% (1,000/day average) |
| Re-verification events | 500/day |

### Compute Estimation

| Task | Duration | Compute |
|---|---|---|
| Document OCR (vendor API) | 1–3 seconds | External API call |
| Liveness check | 2–5 seconds | External API call |
| Watchlist screening | 200–500ms | External API call |
| Internal state machine transition | < 10ms | DB write |
| Total automated path (sequential) | 5–10 seconds | Async pipeline |

**Parallelization opportunity:** OCR and watchlist screening can run in parallel after document upload — reduces pipeline from 10s sequential to ~5s parallel.

### Storage Estimation

| Item | Size | Volume |
|---|---|---|
| Document image (encrypted, S3) | 2 MB avg × 2 docs | 4 MB per application |
| Application metadata (PostgreSQL) | 5 KB | Per application |
| State transition history | 1 KB × ~8 transitions | 8 KB per application |
| Total per application | ~4 MB | — |
| Daily new data | 10,000 × 4 MB | 40 GB/day |
| 2-year retention | 40 GB/day × 730 days | ~29 TB |

Document images stored in S3 with lifecycle rules:
- Active period: S3 Standard
- After closure + 2 years: S3 Glacier (regulatory archive)
- After retention period: automated deletion via S3 lifecycle policy

---

## Read/Write Patterns

| Operation | Pattern | Frequency |
|---|---|---|
| Submit KYC application | Write (state machine init) | 10,000/day |
| Vendor callback / polling | Read + Write (state update) | 30,000–50,000/day |
| Manual review queue read | Read (sorted by priority, age) | 1,000/day |
| KYC status check (upstream polling) | Read | High (10x per application) |
| Audit trail read (compliance) | Read (range query) | Low |
| PII purge | Delete (batch scheduled job) | Daily |

---

## Latency Expectations

| Operation | Target P50 | Target P99 |
|---|---|---|
| Submit application (async start) | < 200ms | < 500ms |
| Vendor OCR response | 1–2s | 5s |
| Full automated KYC completion | < 3 minutes | < 10 minutes |
| Manual review notification to operator | < 5 minutes | < 15 minutes |
| Status query (upstream service) | < 100ms | < 300ms |
| Watchlist screening | < 500ms | < 2s |

---

## Availability Targets

| Component | Availability | Notes |
|---|---|---|
| KYC submission API | 99.9% | Onboarding depends on this |
| State machine processor | 99.5% | Async — brief unavailability does not block submission |
| Manual review dashboard | 99.0% | Operators can queue up; 1hr SLA recovery |
| Vendor API availability | SLA of vendor (typically 99.5%) | Abstracted — retries handle transient failures |

---

## Tradeoffs Acknowledged at Requirements Level

| Decision | Tradeoff |
|---|---|
| Async pipeline | Faster API response (submit = 200ms), but status is eventual — requires polling or push notification |
| Vendor abstraction layer | Adds a mapping layer but allows vendor switching without API changes |
| S3 presigned URL for uploads | Documents never pass through the KYC service — reduces PCI/PII scope; but URL expiry must be managed |
| Manual review queue | Human judgment handles edge cases; adds operational cost and 24h latency |
| Periodic watchlist screening | Real-time screening covers new applications; periodic re-screening covers existing customers but adds batch processing load |

---

## Interview Discussion Points

- **What is KYC and why does it matter?** KYC is a regulatory requirement for financial institutions — you must verify who your customers are before allowing financial transactions. The purpose is to prevent money laundering, fraud, and terrorist financing. Non-compliance = license revocation and heavy fines
- **What is the difference between KYC and AML?** KYC is performed once at onboarding — verify identity before account opening. AML (Anti-Money Laundering) is ongoing monitoring of transactions for suspicious patterns. KYC establishes who the customer is; AML monitors what they do
- **Why is the KYC state machine important?** The state machine ensures that an application cannot skip steps (e.g., become APPROVED without passing the watchlist check). Each transition is a legal fact — it must be recorded, auditable, and non-reversible. A bug that allows APPROVED state without watchlist screening is a regulatory violation
- **How do you handle vendor API outages?** The vendor abstraction layer allows the system to: (1) retry the same vendor with exponential backoff, (2) switch to an alternate vendor configured in fallback order, (3) route to manual review if all vendors are unavailable. No application is left in a stalled state indefinitely
