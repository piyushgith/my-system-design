# 11 — Failure Scenarios & Recovery: KYC / Identity Verification Pipeline

---

## Objective

Document the concrete failure modes for the KYC pipeline, their regulatory and operational impact, detection, and recovery procedures.

---

## Failure 1: All OCR Vendors Unavailable

**Scenario:** DigiLocker is down (government API outage). Onfido is also experiencing degraded service. Both OCR vendors return 503.

**Impact:**
- New KYC applications stall at DOCUMENT_VERIFICATION_PENDING
- No new customer onboarding possible during outage
- Revenue impact: every minute of onboarding downtime = lost users

**Detection:**
- Circuit breaker opens after 5 failures on both DigiLocker and Onfido
- `kyc:vendor:circuit:digilocker = OPEN`, `kyc:vendor:circuit:onfido = OPEN`
- Alert: "All OCR vendors unavailable — KYC pipeline stalled"
- Outbox relay consumer lag for `kyc.step.ocr.pending` growing

**Recovery:**
1. OCR worker routes all pending OCR steps to MANUAL_REVIEW (vendor_unavailable reason)
2. Manual review officers perform document verification manually (using document images in S3)
3. When vendor service recovers: circuit breaker half-open probe succeeds → close circuit
4. Drain MANUAL_REVIEW queue: re-attempt vendor OCR for cases still pending operator decision
5. SLA breach: if outage > 2 hours, communicate ETA to Onboarding team

**RTO:** 30 minutes (route to manual review) | **RPO:** Zero (applications not lost, just delayed)

---

## Failure 2: Invalid State Transition Attempted

**Scenario:** A bug causes the pipeline to attempt transitioning an APPROVED application to LIVENESS_PENDING — a clearly invalid transition.

**Impact:**
- If the state machine allows it: data corruption, regulatory audit finding
- Application in incorrect state — customer may lose their APPROVED status

**Detection:**
- State machine throws `InvalidTransitionException` — caught at application layer
- Logged as ERROR with stack trace and application_id
- Alert: "Invalid state transition attempted" — P0 alert

**Recovery:**
1. The application's state in DB is unchanged (exception before any UPDATE)
2. Investigate bug: which event triggered the invalid transition, which code path
3. If application is in correct state: no recovery needed
4. If application was incorrectly transitioned (worse case): manual state correction via admin API (`kyc:admin` scope) + create state_transition record explaining the correction

**Prevention:**
- State machine validates transitions in code before any DB operation
- State machine transitions tested exhaustively in unit tests (every valid and invalid transition)
- Database-level check: trigger that validates new status is a valid transition from current status

---

## Failure 3: KMS Unavailable (PII Unreadable)

**Scenario:** AWS KMS experiences an outage. The pipeline cannot decrypt personal_data for vendor calls or cannot encrypt new applications.

**Impact:**
- New applications: cannot encrypt PII → INSERT fails → submission returns 500
- In-flight applications: cannot decrypt PII for vendor calls → pipeline stalls
- Manual review: reviewers cannot read PII

**Detection:**
- KMS `DecryptException` → ERROR log + metric `kyc_kms_errors_total`
- Alert: "KMS decryption failures > 5 in 1 minute"

**Recovery:**
1. KMS is AWS-managed — SLA 99.999%. Outages are rare but brief (< 5 minutes)
2. During outage: new submissions queue (202 Accepted, pipeline starts when KMS recovers)
3. Pending vendor calls: retry with backoff — KMS SDK retries automatically
4. If prolonged (> 15 minutes): activate KMS disaster recovery (cross-region KMS endpoint)

**Pre-emptive mitigation:** AWS KMS SDK caches decrypted data keys in memory for 5 minutes. Brief KMS outages are invisible if the DEK is already cached from a recent decryption. Configure `CachingMaterialsProvider` with 5-minute TTL.

**RTO:** 5 minutes (cache covers brief outage); 15 minutes for prolonged | **RPO:** Zero

---

## Failure 4: Pipeline Message Stuck in Kafka (Consumer Group Rebalance Loop)

**Scenario:** The OCR worker pod crashes repeatedly (OOMKilled). Kubernetes restarts the pod, but it crashes again before committing the Kafka offset. The same `kyc.step.ocr.pending` message is re-delivered on each restart.

**Impact:**
- The OCR step for one application never completes — stuck in IN_PROGRESS forever
- Consumer group rebalances on every pod death — slows down other applications

**Detection:**
- `kyc-ocr-worker` consumer group lag growing for 1 specific partition
- Pod OOMKilled events in Kubernetes events
- `kyc.step.ocr.pending` consumer lag > 50

**Recovery:**
1. Kubernetes OOMKilled → fix: increase pod memory limit or investigate memory leak
2. The stuck message: consumer checks DB before calling vendor. If step is IN_PROGRESS for > 30 minutes (timeout check in orchestrator), reset step to PENDING and republish the pending event
3. Stalled step detection: `SELECT * FROM verification_steps WHERE status='IN_PROGRESS' AND started_at < now() - interval '30 minutes'` — scheduled job resets these and re-emits Kafka trigger events

**RTO:** 30 minutes (automatic stale step reset) | **RPO:** Zero

---

## Failure 5: Watchlist Vendor Returns False Positive

**Scenario:** LexisNexis incorrectly flags a legitimate customer as a PEP match (name similarity: "Mohammed Ali" matches a PEP named "Muhammad Ali Rashid").

**Impact:**
- Legitimate customer routed to MANUAL_REVIEW
- Delayed onboarding (up to 24 hours)
- Customer dissatisfaction

**Detection:**
- High WATCHLIST_HIT rate: `SELECT COUNT(*) / total WHERE routing_reason = 'WATCHLIST_HIT'` > 5% → investigate
- Compliance officer feedback: "most of these are false positives"

**Recovery:**
1. Compliance officer reviews and approves (MANUAL_REVIEW → APPROVED)
2. Systemic fix: tune watchlist matching threshold — raise minimum match_score from 0.7 to 0.85 for auto-routing to MANUAL_REVIEW
3. Custom entity exclusion: build a "known false positives" suppression list maintained by compliance team
4. Monitor false positive rate weekly as a compliance metric

**This is not a system failure** — it's a tuning problem. The state machine and pipeline work correctly; the watchlist matching logic needs calibration.

---

## Failure 6: Document Image Corrupted (S3 Object Corrupted)

**Scenario:** S3 ETag mismatch detected when downloading document for OCR — the object bytes don't match the expected checksum.

**Impact:**
- OCR vendor receives corrupted image → extraction fails or returns garbage data
- Application may be wrongly rejected

**Detection:**
- S3 `GetObject` response includes ETag; application verifies content hash
- Hash mismatch → `DOCUMENT_CORRUPT` error logged

**Recovery:**
1. Invalidate the document reference — mark `document_reference.is_purged=true` (soft)
2. Application transitions to DOCUMENT_REJECTED with reason `DOCUMENT_CORRUPT`
3. Notify user: "Document upload failed, please re-upload"
4. User re-uploads document → new document_reference → new OCR attempt
5. Investigate S3 corruption root cause (extremely rare — S3 has 11 nines durability)

**Prevention:** S3 multipart upload with checksum (CRC32C) validation — upload fails immediately if bytes are corrupted in transit.

---

## Failure 7: Manual Review Queue Overflow (Compliance SLA Breach)

**Scenario:** Campaign day: 5,000 applications routed to MANUAL_REVIEW (10% of 50,000). The compliance team has 10 officers processing 200 cases/day. Queue depth = 5,000 / 200 = 25 days of backlog.

**Impact:**
- Regulatory SLA breach: RBI KYC Master Direction requires review completion within 7 days
- Revenue impact: 5,000 users waiting up to 25 days for account activation

**Detection:**
- `manual_review_queue` age > 24 hours: alert
- Queue depth > 500: P1 alert

**Recovery (immediate):**
1. Temporary additional reviewers (outsourced compliance vendor)
2. Re-screen watchlist hits with updated threshold — clear false positives automatically
3. Prioritize HIGH priority cases (genuine sanctions matches) — MEDIUM/LOW auto-expire after 7 days with re-screening

**Prevention:**
- Risk-based routing: only EXACT watchlist matches go to MANUAL_REVIEW; FUZZY matches below 0.85 score auto-approve with monitoring
- Capacity planning: pre-schedule additional review capacity for known campaign dates

---

## RTO / RPO Summary

| Failure | RTO | RPO | Data Loss? |
|---|---|---|---|
| All OCR vendors down | 30 minutes (manual review routing) | 0 | None |
| Invalid state transition | Immediate (rejected by state machine) | 0 | None |
| KMS unavailable | 5–15 minutes | 0 | None |
| Consumer rebalance loop | 30 minutes (stale step reset) | 0 | None |
| Watchlist false positive | 24 hours (human review) | 0 | None — operational delay |
| Document corruption | Minutes (user re-uploads) | 0 | Document data |
| Manual review overflow | Days (capacity planning failure) | 0 | None — regulatory risk |

---

## Interview Discussion Points

- **What is your worst-case scenario?** KMS unavailable + all OCR vendors down simultaneously. No new applications can be submitted, and in-flight applications stall. Mitigation: KMS cross-region failover, DigiLocker + Onfido + Jumio as three vendor levels. Probability of all failing simultaneously is extremely low
- **How do you prevent a corrupt state machine from wrongly approving applications?** Defense in depth: (1) State machine validates transitions in code, (2) Watchlist step is required by regulation — APPROVED state cannot be reached without a WATCHLIST_SCREENING step in PASS/MANUAL status, (3) Automated audit: daily job checks all APPROVED applications have a completed watchlist step — alerts on any that don't
- **How do you handle the regulatory breach if KYC data is accessed without authorization?** Security incident response: (1) Immediately revoke the compromised access token, (2) Audit log shows exactly which application_ids were accessed and when, (3) Notify affected customers within 72 hours (GDPR/DPDP requirement), (4) Report to CERT-In and RBI within mandated timeframe, (5) Engage data protection officer (DPO)
- **What is your chaos engineering test for KYC?** Quarterly: (1) Kill all OCR worker pods — verify manual review routing kicks in within 30 minutes, (2) Force a watchlist vendor timeout — verify fallback vendor activates, (3) Flood review queue — verify priority escalation alerts fire, (4) Delete a KMS key version — verify crypto-erasure doesn't break active applications
