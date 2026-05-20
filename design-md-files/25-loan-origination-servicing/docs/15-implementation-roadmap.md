# 15 — Implementation Roadmap: Loan Origination & Servicing System

## Objective

Phase-wise implementation plan from MVP (personal loan, basic workflow) to production-grade lending platform with multiple products, regulatory compliance, and automated collections.

---

## Phase Overview

| Phase | Scope | Timeline | Team Size |
|-------|-------|----------|-----------|
| MVP | Personal loan, manual underwriting, basic disbursement | 10 weeks | 3-4 engineers |
| V1 | Auto-underwriting, maker-checker, EMI collection | 14 weeks | 5-6 engineers |
| V2 | Home loan + BNPL, restructuring, collections workflow, regulatory | 16 weeks | 8-10 engineers |
| V3 | Scale: 2M loans, Spring Batch EMI, NPA management, microservices extraction | 20 weeks | 12+ engineers |

---

## MVP: Personal Loan, Manual Workflow

**Goal:** End-to-end loan lifecycle for one product, manually triggered at each stage. Prove the domain model and state machine are correct before adding automation.

### Features

- Borrower registration and application submission
- Document upload (to S3)
- Manual underwriting (loan officer views application, clicks approve/reject)
- No maker-checker (single approver for MVP)
- Offer generation and borrower acceptance
- Manual disbursement trigger (ops team confirms, system processes)
- Loan account creation and amortization schedule generation
- Manual EMI recording (ops team enters payment received)
- Basic loan status API for borrowers
- PostgreSQL only (no Kafka, no Redis)

### Architecture

- Single Spring Boot monolith
- PostgreSQL for all data
- S3 for documents
- Basic Spring Security (JWT, roles: BORROWER, LOAN_OFFICER)
- H2 for testing

### Success Criteria

- Full loan lifecycle completable without manual DB queries
- Amortization schedule sums to correct total (reconciliation test passes)
- 10 concurrent loan officers can use the system without data corruption
- Audit log captures every state transition

### Risks

- Amortization formula bugs — correctness must be 100% before EMI automation
- State machine gaps — all invalid transitions explicitly rejected

---

## V1: Auto-Underwriting, Maker-Checker, NACH EMI Collection

**Goal:** Production-ready for personal loans with automated credit decisioning and EMI collection.

### Features Added

- Credit rules engine (DTI check, bureau score bands, blacklist check)
- Bureau integration (CIBIL API — async polling or webhook)
- Maker-checker workflow (full four-eyes enforcement)
- Offer validity window with auto-expiry
- Kafka for async workflows (bureau check, notification, audit)
- Automated NACH mandate setup on offer acceptance
- EMI batch job (K8s CronJob) — NACH file generation
- NACH result file processing (T+1 bank response)
- Bounce handling and retry logic (2 retries, then collections trigger)
- Prepayment API (partial and full foreclosure)
- Redis for idempotency and loan summary cache
- Notification Service (SMS + email via SendGrid/Twilio)
- Disbursement saga with timeout handler

### Architecture Evolution

- Kafka introduced (5 topics: application-events, underwriting-events, disbursement-events, emi-collection-events, notification-commands)
- Redis introduced (idempotency, loan summary cache, bureau cache)
- Outbox pattern for reliable event publication
- Spring Batch for NACH result file processing
- PgBouncer for connection pooling
- Kubernetes deployment (3 pods per service, auto-scaling for origination)

### Success Criteria

- 1,000 applications processed end-to-end in staging
- EMI batch processes 100 loans in < 5 minutes
- Bureau API failure triggers correct async retry behavior
- Maker-checker prevents same-person approval (tested with role permutation)
- Zero reconciliation discrepancies after 100 loan lifecycle completions

### Risks

- Bureau API integration complexity (CIBIL has inconsistent sandbox)
- NACH mandate setup via NPCI sandbox (limited test environment)
- Kafka consumer ordering guarantees (test concurrent events for same loan)

---

## V2: Multiple Products, Restructuring, Collections, Regulatory

**Goal:** Full lending platform supporting home loans, BNPL, loan restructuring, collections workflow, and RBI regulatory reporting.

### Features Added

- Home loan product (LTV calculation, collateral capture)
- BNPL product (merchant disbursement, short tenure)
- Loan restructuring with maker-checker (tenure extension, EMI reduction)
- NPA classification (90 DPD trigger, daily batch job)
- Collections workflow (telecalling assignment, escalation tiers)
- Legal workflow trigger (90+ DPD)
- Settlement offer workflow (collections agents offer reduced payoff amount)
- Write-off workflow (finance + CFO approval)
- Regulatory reporting: RBI SMA classification, NPA portfolio report
- Data archival: old applications + documents to S3 Glacier
- Row-level security (PostgreSQL RLS)
- Audit log HMAC chain (tamper detection)

### Architecture Evolution

- Origination and Servicing modules clearly separated (package-level DDD boundaries)
- Collections Service as semi-independent module (owns its own DB tables)
- Spring Batch for regulatory report generation
- Elasticsearch for back-office search (search applications by name, PAN, status)
- DR region warm standby (PostgreSQL read replica + Kafka MirrorMaker)
- PII encryption with Vault Transit engine

### Success Criteria

- NPA classification runs correctly daily without manual intervention
- Collections escalation moves through all tiers (verified with test loans at DPD 30, 60, 90)
- Regulatory reports match manually computed figures (spot-check on 100 loans)
- DR failover tested successfully (quarterly DR drill)
- Zero write-offs processed without CFO checker approval (role test)

---

## V3: Scale, Microservices Extraction, Advanced Analytics

**Goal:** Scale to 2M active loans, 200,000 monthly applications, extract high-traffic services.

### Features Added

- Scale testing: 2M active loan simulation in staging
- EMI batch: distributed processing (Apache Spark or parallel Spring Batch)
- Elasticsearch for borrower and loan search at scale
- Credit policy management UI (risk managers configure rules without code deploy)
- Behavioral scoring integration (transaction history, digital footprint)
- Portfolio analytics dashboard (NPA trend, cohort analysis by vintage)
- Open Banking integration (AA framework — account aggregator for income verification)
- Pre-approved offers (outbound campaign based on existing borrower creditworthiness)
- Multi-lender platform support (origination for partner lenders)

### Microservices Extraction (Evidence-Driven)

Extract only when evidence exists:

| Service | Extract When | Reason |
|---------|-------------|--------|
| Notification Service | V3 | Independent product, independent team |
| Collections Service | V3 | Separate compliance team ownership |
| Credit Decision Engine | V2-V3 | ML team deploys models independently |
| Regulatory Reporting | V3 | Compliance team needs independent control |

Origination + Servicing remain in monolith until team size or scaling forces separation.

### Infrastructure Evolution

| Component | MVP | V1 | V2 | V3 |
|-----------|-----|----|----|-----|
| Services | 1 monolith | 1 monolith + 2 sidecars | Modular monolith + 2 microservices | Modular monolith + 4 microservices |
| PostgreSQL | RDS Single | RDS + 2 replicas | RDS + 3 replicas | RDS + 3 replicas (consider Citus at 10M loans) |
| Kafka | None | 3-broker MSK | 6-broker MSK | 6-broker MSK + MirrorMaker |
| Redis | None | ElastiCache single | ElastiCache cluster | ElastiCache cluster |
| App pods | 5 | 15 | 30 | 60 |

---

## Implementation Sequence Within Each Phase

1. **Domain model + unit tests** — EMI calculation, state machine, amortization formula. 100% coverage before any infrastructure.
2. **Persistence layer** — schema creation, migration scripts (Flyway), audit log.
3. **Core API** — application submission, status, document upload.
4. **Underwriting workflow** — rules engine, bureau integration.
5. **Disbursement** — ledger integration, bank transfer, saga.
6. **Servicing APIs** — loan account, schedule, prepayment.
7. **EMI batch** — scheduler, NACH integration, result processing.
8. **Notifications** — integrate at the end (side concern, not core).
9. **Load testing** — validate throughput at each phase before declaring done.
10. **Security hardening** — auth, RLS, secrets, PII masking.
11. **Reconciliation scripts** — run before go-live to validate data integrity.
12. **Operational runbooks** — document before go-live. No go-live without runbooks.

**Never go live without:**
- A tested reconciliation script that can validate financial correctness
- A DR procedure with tested recovery
- An on-call rotation with runbooks for every P0 scenario
- Finance team sign-off on amortization formula accuracy
