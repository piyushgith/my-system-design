# 15 — Implementation Roadmap: KYC / Identity Verification Pipeline

---

## Objective

Define the phased implementation plan from MVP through production-grade compliance. Each phase is deployable and meets regulatory minimum requirements.

---

## MVP (Weeks 1–8): Basic Automated KYC

### Goal: End-to-end KYC for one document type (Aadhaar) with automated pipeline

### Features

- KYC application submission API (REST, 202 Accepted)
- Document reference model (S3 presigned URL upload)
- State machine (7 core states: SUBMITTED → DOCUMENT_VERIFIED → LIVENESS_PASSED → WATCHLIST_CLEAR → APPROVED / REJECTED / MANUAL_REVIEW)
- One OCR vendor integration: DigiLocker for Aadhaar
- One liveness vendor: Onfido
- One watchlist vendor: LexisNexis
- State transition history (append-only)
- PII encryption at rest (AES-256-GCM + KMS)
- Manual review queue with basic dashboard
- Kafka: `kyc.outcome.decided` event to Onboarding
- Status polling endpoint

### Architecture

- Single Spring Boot deployment (KYC API + Pipeline worker)
- PostgreSQL (single instance)
- Redis (single instance for idempotency + status cache)
- Kafka (3 brokers)
- AWS KMS for encryption

### Infra

- Docker Compose for local dev
- Kubernetes in single namespace
- Basic CI: tests + image build

### Risks

- Only Aadhaar support — PAN, Passport required for V1
- No fallback vendor — DigiLocker outage = pipeline stall
- Manual review dashboard is basic (no SLA tracking, no bulk actions)

### Team

- 2–3 backend engineers + 1 compliance consultant (for regulatory requirements)

---

## V1 (Weeks 9–20): Multi-Document + Production Hardening

### Goal: Production-ready with full document support, vendor fallback, and compliance reporting

### Features

- **Additional documents:** PAN card (NSDL API), Passport (government API or Onfido), Driver's License
- **Vendor fallback:** Jumio as OCR fallback when DigiLocker/primary fails; circuit breaker per vendor
- **KYC tiers:** BASIC (Aadhaar OTP only), STANDARD (doc + liveness), FULL (+ enhanced watchlist + income proof)
- **Webhook support:** Onfido async callback processing
- **Re-verification:** Trigger re-KYC for existing customers (same state machine, new application_id)
- **Compliance reports:** Weekly automated report (approval rates, watchlist hits, review SLAs)
- **PII purge:** Scheduled job for retention-expired applications
- **Duplicate identity detection:** Document hash deduplication
- **Enhanced monitoring:** Grafana dashboards, vendor performance metrics, P0 alerts
- **Compliance test suite:** State machine invariants, PII encryption verification

### Architecture

- Separate KYC API and Pipeline Worker deployments
- PostgreSQL primary + 1 read replica
- Redis Cluster (3 nodes)
- Secrets Manager for vendor API keys
- Vendor sandbox for staging environment

### Infra

- Full CI/CD with compliance gate (manual approval before production deploy)
- Staging environment with anonymized data
- PodDisruptionBudget + HPA

### Risks

- Multiple vendor integrations increase test surface area
- Compliance officer sign-off gate may slow deployment velocity
- Webhook processing adds complexity (signature validation, idempotency)

### Team

- 3–4 backend engineers + 1 compliance officer (part-time) + 1 SRE

---

## V2 (Months 6–12): Continuous Monitoring + Risk-Based KYC

### Goal: Ongoing compliance (not just onboarding KYC) + risk-based tiering

### Features

- **Continuous watchlist monitoring:** Periodic re-screening of all APPROVED customers (monthly for low-risk, weekly for high-risk)
- **Event-driven watchlist updates:** LexisNexis change events trigger immediate re-screening for new list additions
- **Risk-Based KYC (RBA):** KYC tier dynamically selected based on transaction risk score from Risk Engine
- **C-KYC integration:** For returning Indian financial customers with existing C-KYC ID — reduced friction onboarding
- **Video KYC:** Agent-assisted video verification for high-value accounts or borderline manual review cases
- **Biometric deduplication:** Facial embedding comparison against existing customer base — detect multi-identity fraud
- **Data residency:** Support for EU customers (document data must stay in EU AWS region)
- **GDPR right to access API:** User-facing endpoint to retrieve their own KYC data (via Onboarding portal)
- **Automated false positive clearing:** ML-based triage model for watchlist hits

### Architecture

- Separate OCR Worker, Liveness Worker, Watchlist Worker as distinct Kubernetes deployments
- Multi-region (one region primary, one region standby for DR)
- Biometric vault: separate secure service for facial embedding storage and comparison

### Infra

- Multi-region AWS setup
- AWS EventBridge for watchlist update event routing
- Separate PostgreSQL for biometric vault (stricter access controls)

### Team

- 5–6 backend engineers + 1 ML engineer + 1 compliance officer (full-time) + 1 SRE

---

## V3 (Year 2+): Enterprise Scale + Cross-Jurisdiction

### Goal: Serve multiple jurisdictions with different regulatory frameworks and millions of customers

### Features

- **Cross-jurisdiction KYC:** Different document sets, vendor routing rules, and retention policies per country (India, UAE, Singapore, EU)
- **Regulatory reporting API:** Direct submission to regulatory bodies (SEBI, RBI, FCA) via their APIs
- **eIDAS/DigiLocker federation:** Federated identity for EU users using eIDAS-certified providers
- **KYC microservice:** Extract into standalone service with multi-tenant support
- **Real-time watchlist:** WebSocket-based watchlist update streaming — < 1 second from new list entry to customer screening
- **AI-powered document forgery detection:** On-device ML + server-side deepfake detection layer before vendor

### Team

- 10–15 engineers across KYC, compliance tech, and platform

---

## Phase Summary

| Phase | Capability | Duration | Regulatory Coverage |
|---|---|---|---|
| MVP | Single-document Aadhaar KYC, basic pipeline | 8 weeks | RBI KYC (basic) |
| V1 | Multi-document, vendor fallback, compliance reports | 12 weeks | Full RBI KYC |
| V2 | Continuous monitoring, risk-based, C-KYC | 6 months | RBI + FATF Risk-Based |
| V3 | Multi-jurisdiction, real-time watchlist | 12 months | Global (FCA, MAS, SEBI) |

---

## What NOT to Build Prematurely

| Feature | Why Defer |
|---|---|
| Biometric deduplication | Complex ML infrastructure; most KYC fraud is document forgery, not multi-identity |
| Video KYC | High infrastructure cost (WebRTC, agent scheduling); reserve for < 1% of cases |
| C-KYC integration | Reduce friction for returning customers; not needed at startup (most users don't have C-KYC ID) |
| Multi-jurisdiction | Add complexity only when entering a new market |
| Real-time watchlist streaming | Batch re-screening (monthly) meets regulatory minimum; real-time is gold-plating |

---

## Interview Discussion Points

- **What is the minimum viable KYC for a new Indian fintech?** Aadhaar OTP verification via DigiLocker (eKYC) — instant, fully digital, no manual review needed for basic account limits. This satisfies RBI's basic KYC requirements for low-risk accounts (< ₹10,000 monthly limit). Add full KYC (document + liveness + watchlist) for higher limits
- **How long does it take to go from MVP to regulatory approval?** The technical implementation can be done in 8 weeks. The regulatory approval process (RBI in-principle approval, CERT-In security audit, RBI inspection) takes 6–18 months. Technical development and regulatory approval run in parallel — submit regulatory documentation based on the MVP design before the code is finished
- **What is the biggest engineering challenge in KYC?** Not the technology — it's the vendor API reliability and the edge cases in document extraction. Real-world document images are: blurry, partially covered, laminated (glare), damaged, older formats. The OCR engine needs to handle all these gracefully, and the manual review team needs to be trained to handle the cases automation cannot
