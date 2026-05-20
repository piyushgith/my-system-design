# 16 — Advanced Improvements & Architecture Critique: KYC / Identity Verification Pipeline

---

## Objective

Critically evaluate the designed KYC architecture, surface weaknesses, and propose advanced improvements for staff-level engineering discussions.

---

## Architecture Critique

### Weakness 1: Vendor Lock-In Despite Abstraction Layer

**What it is:** The vendor abstraction layer hides vendor-specific APIs — but not vendor-specific data requirements. DigiLocker requires an Aadhaar number + OTP; Onfido requires a document image upload. The abstraction leaks at the input level.

**Impact:** Switching vendors for a given document type requires updating both the adapter AND the data collection flow in the Onboarding service. The "zero coupling" promise of the adapter is partially broken.

**Solution:** Define a canonical input model per verification type:
- `DocumentOcrInput { document_images: List<S3Key>, document_type, user_hint_name, user_hint_dob }`
- Each adapter extracts what it needs from this canonical input
- The Onboarding service always provides the canonical input — never vendor-specific inputs

This achieves true vendor-agnostic input. Switching from DigiLocker to Onfido: zero change in Onboarding service.

---

### Weakness 2: State Machine in Application Code (Not Enforced at DB Level)

**What it is:** The state machine is enforced in Java code. A bug that bypasses the service layer (e.g., a maintenance script that directly updates `kyc_applications.status`) can create invalid states.

**Risk:** Directly setting status=APPROVED without a corresponding state_transition row is a regulatory violation. A DBA running a hotfix could inadvertently create applications in APPROVED state without the required verification steps.

**Solution:** PostgreSQL trigger that validates:
1. Every UPDATE to `kyc_applications.status` must have a corresponding INSERT into `state_transitions` within the same transaction
2. The new status must be a valid transition from the current status (checked against a `valid_transitions` reference table)

This makes the state machine enforcement database-level, not just application-level.

---

### Weakness 3: PII Decryption in Pipeline Worker Pod Memory

**What it is:** The pipeline worker decrypts PII (personal data blob, document images) in memory to call vendor APIs. This decrypted PII exists in JVM heap memory for the duration of the vendor API call.

**Risk:**
- JVM heap dump (for debugging) captures PII in plaintext
- Kubernetes ephemeral storage (crash dumps) could capture PII
- If the pod is compromised, the attacker has plaintext PII

**Mitigation:**
1. **Confidential computing:** Run worker pods on AWS Nitro Enclaves — isolated, attestable execution environment where even AWS staff cannot inspect memory
2. **Minimal exposure window:** Immediately release PII object references after vendor call; explicitly call `Arrays.fill(piiBytes, (byte)0)` to zero the decrypted bytes
3. **Heap dump protection:** JVM flags: `-XX:+DisableAttachMechanism` (prevent remote heap dumps); `-Djdk.attach.allowAttachSelf=false`

---

### Weakness 4: Manual Review Queue Has No Automated Quality Assurance

**What it is:** Compliance officers make manual review decisions. There is no automated check to verify that their decisions are consistent with regulatory requirements or their own historical decisions.

**Risk:** A rogue officer could approve a legitimate sanctions match. Different officers may apply different standards (one approves name similarity > 0.85; another requires > 0.95).

**Improvement:**
1. **Dual-control for HIGH priority:** Sanctions matches require approval from two officers (maker-checker on manual review)
2. **Decision consistency scoring:** Weekly ML analysis of officer decisions — flag officers whose approval rate diverges significantly from peers for the same routing_reason
3. **Audit sampling:** Random sample of 5% of manual decisions reviewed by the head of compliance monthly

---

### Weakness 5: Continuous Watchlist Re-Screening Is Batch, Not Real-Time

**What it is:** Periodic monthly re-screening is adequate for RBI minimum requirements but misses the window between list publication and the next batch run.

**Scenario:** A customer is added to the OFAC SDN list on Day 1. The monthly batch runs on Day 30. The customer has had 30 days of unrestricted account activity.

**Risk:** Regulatory fine and reputational damage for failing to freeze a sanctioned customer's account promptly.

**Solution (V2):** Event-driven watchlist monitoring:
- LexisNexis provides a webhook on list updates
- `WatchlistUpdateConsumer` receives the event, extracts newly added entities
- Comparison job: for each new list entry, search all APPROVED customers using deterministic document hash (not name search — names are encrypted)
- High-efficiency solution: maintain a secondary index of `document_id_hash` for all APPROVED customers — O(1) lookup per new watchlist entry

**Trade-off:** Real-time monitoring adds a persistent Kafka consumer and comparison job — operational complexity vs. regulatory risk reduction.

---

## Advanced Improvement Proposals

### Improvement 1: Temporal Workflow Engine for Complex KYC Flows

For Video KYC and multi-agent KYC (where agent identity is verified, then customer identity, then cross-matching):

Temporal workflow:
1. `SubmitKycStep` — create application
2. `ScheduleAgentSession` — book compliance officer for video call
3. `ConductVideoKyc` — record session, AI transcription
4. `ManualDecision` — officer submits decision from video evidence
5. `WatchlistVerification` — watchlist run on video-captured data

Temporal provides: automatic retry on each step, durable state across step boundaries, visual workflow history, easy timeout and compensation handling.

**When justified:** Only for Video KYC (< 1% of applications). Standard KYC is fast enough that Temporal's overhead is not justified.

---

### Improvement 2: Federated KYC with Verifiable Credentials

Self-Sovereign Identity (SSI): Instead of the KYC service storing verified identity, the user receives a cryptographically signed Verifiable Credential (W3C VC standard).

**How it works:**
1. KYC service verifies identity (current flow)
2. Issue a VC: `{"holder": "user_did", "claims": {"name": "...", "dob": "...", "kyc_tier": "STANDARD", "verified_at": "..."}}`
3. Sign the VC with the organization's DID (Decentralized Identifier)
4. User stores the VC in a digital wallet (IOTA, Polygon ID)
5. For re-KYC at another institution: user presents the VC → institution verifies signature → no re-verification needed

**Impact:** Eliminates redundant KYC across institutions. Regulatory compliance: user controls their data, reducing the organization's PII custodianship responsibility.

**Status:** Emerging standard; not yet required by RBI but being piloted by NPCI for account aggregation.

---

### Improvement 3: Homomorphic Encryption for Watchlist Matching

**Problem:** Current approach decrypts the user's name/DOB in memory to call the watchlist vendor. The vendor sees plaintext data.

**Solution:** Homomorphic Encryption (HE) — compute on encrypted data without decryption.

`EncryptedName ⊕ WatchlistEntry → MatchScore` (computed without revealing the name)

**Status:** HE is computationally expensive (100–1000x slower than plaintext). Current hardware makes it impractical for production at KYC scale. Research direction for 5–10 years from now.

**Near-term alternative:** Zero-Knowledge Proofs (ZKP) — prove "this person is not on the OFAC list" without revealing their name. Companies like Sismo and Polygon ID are building ZKP identity infrastructure.

---

## Scaling Limits

| Constraint | Limit | Resolution |
|---|---|---|
| Human manual review throughput | ~200 cases/day per officer | Pre-scale with compliance vendor, automate false positive clearing |
| Vendor OCR rate limits | Onfido 50 RPS | Negotiate enterprise contract, add Jumio as co-primary |
| PostgreSQL state transitions | ~500 writes/second (10K apps × 8 transitions / 8 hours) | Trivial — partitioning only needed at 10x growth |
| KMS DEK generation | ~100 RPS per region | AWS KMS is highly available; DEK caching reduces calls |
| S3 document storage | 29 TB per year | S3 is effectively unlimited; lifecycle policies manage cost |

---

## Tech Debt Risks

| Debt | Risk | Mitigation |
|---|---|---|
| Vendor adapter leaks at input level | Vendor switching requires Onboarding changes | Canonical input model (V2) |
| State machine in application code only | Direct DB access bypasses validation | PostgreSQL trigger (V1) |
| No dual-control for HIGH review | Single officer can approve sanctions match | Maker-checker for HIGH priority (V1) |
| Batch watchlist re-screening | 30-day window for newly sanctioned customers | Event-driven watchlist updates (V2) |
| No biometric deduplication | Multi-identity fraud possible | Facial embedding vault (V2) |

---

## Operational Burdens

| Burden | Frequency | Who Owns |
|---|---|---|
| Vendor API key rotation | Every 90 days | SRE + Security |
| Compliance report review | Weekly | Compliance Officer |
| Manual review SLA monitoring | Daily | Compliance Manager |
| Watchlist data feed update | Daily/weekly (vendor-specific) | Compliance Engineering |
| PII purge verification | Monthly audit | Compliance + Engineering |
| Penetration testing (KYC flows) | Quarterly | Security team |
| Regulatory inspection prep | Annual | Compliance Officer + Engineering |

---

## What a Senior Interviewer Would Challenge

| Challenge | Expected Response |
|---|---|
| "Your crypto-erasure approach — how do you prove to a regulator the data is truly erased?" | KMS key deletion is irreversible and provable: AWS CloudTrail shows `ScheduleKeyDeletion` event with timestamp. The encrypted blob is provably irrecoverable without the key. This is accepted by GDPR supervisory authorities as a valid erasure technique |
| "How do you handle the case where the liveness check vendor adds a new check type (video vs photo) mid-contract?" | VendorAdapter is the isolation point. The adapter's `performLivenessCheck` interface doesn't specify HOW the vendor checks liveness — only that it accepts a media reference and returns a `LivenessResult`. The adapter implementation handles vendor-specific check type routing |
| "Your audit trail stores who approved each application. What if a compliance officer was compromised?" | Dual-control for HIGH priority cases (two officers must approve). Audit of audit: head of compliance reviews 5% of decisions monthly. Anomaly detection: machine learning flags officers whose decisions diverge from peer pattern. These are people-process controls — technology cannot fully substitute for human oversight in a regulated function |
| "Can your KYC service become a shared identity provider for other products in the group?" | Yes — the KYC outcome (APPROVED, tier, verified_at) can be exposed as a verified claims API. Other products within the group query `GET /kyc/users/{user_id}/claims` to get the verified identity level. This eliminates re-KYC for the same user across multiple products. The KYC service becomes an Identity Provider (IdP) for the group. This is the C-KYC concept applied at group level |
