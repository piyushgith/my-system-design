# 15 — Implementation Roadmap: Banking Core System

---

## Objective

Define phased implementation from a basic account management system to a full-featured banking core — aligned with regulatory licensing milestones, team growth, and increasing technical complexity.

---

## Pre-Development: Regulatory Timeline

Banking cannot launch without regulatory approvals. These run in parallel with development:

| License | Authority | Timeline | Required For |
|---|---|---|---|
| NBFC license | RBI | 6-12 months | Lending products |
| Payment Bank license | RBI | 12-18 months | Deposits + payments |
| Small Finance Bank | RBI | 18-24 months | Full banking |
| Banking Correspondent | Existing bank partnership | 2-3 months | Piggyback on existing bank |

**Practical path for neobank**: start as Banking Correspondent of an existing bank. Use their license. Focus on technology and UX. Get own license as you scale.

---

## Phase 0 — Foundation: Account + Ledger (Months 1-4)

### Goal

Build the foundational data model correctly. Get the ledger right from day 1. Get compliance foundations in place.

### Features

- Account creation (savings account type only)
- Customer KYC (Aadhaar + PAN via CKYC integration)
- Account balance management
- Ledger: double-entry bookkeeping (credit/debit entries)
- Fund deposit via bank transfer (NEFT from external bank)
- Basic fund transfer (between our own accounts)
- Transaction history
- Account statement (last 90 days)
- Staff portal: account management, basic reports

### Architecture

- Spring Boot modular monolith
- Module structure:
  - `account` — account lifecycle
  - `ledger` — double-entry ledger
  - `kyc` — KYC verification
  - `auth` — authentication (staff + customer)
- Postgres Multi-AZ (from day 1 — financial data)
- Redis: OTP, sessions, rate limiting
- No Kafka yet (synchronous only)

### Non-Negotiables (Must Get Right)

1. Double-entry ledger: every credit has a corresponding debit
2. Integer amounts (paise): never float
3. Account number generation: unique, check digit validation
4. IFSC validation: via RBI IFSC master
5. Audit log: append-only, from the very first transaction

### Compliance

- KYC per RBI KYC Master Direction
- Data storage: Indian data center only
- Customer consent logging (DPDP Act)
- Session timeout: 15 minutes

### Team

- 3 backend engineers
- 1 compliance + legal
- 1 QA (banking QA is harder than web QA)

### Success Criteria

- 10,000 accounts, zero ledger imbalances, pass initial RBI inspection

---

## Phase 1 — V1: Payment Rails (Months 5-10)

### Goal

Customers can send and receive money via standard payment rails.

### Features Added

- NEFT send (outbound to other banks)
- RTGS send (high-value transfers)
- IMPS (24x7, real-time)
- UPI (Unified Payments Interface)
- Beneficiary management
- Transfer limits (daily, per-transaction)
- SMS + email notifications
- Basic maker-checker (for admin actions, not customer transfers yet)
- Net banking portal (basic)
- Mobile banking app API

### Architecture Changes

- NEFT/RTGS adapter module (NPCI API integration)
- UPI module (NPCI UPI integration)
- Kafka introduced: `banking.transaction.*` topics for fan-out
- Outbox pattern for transaction events
- Notification service (async, Kafka-backed)
- PgBouncer: connection pooling
- Rate limiting: Redis token bucket per customer action

### Compliance

- NPCI membership (for NEFT/RTGS/UPI)
- AML: basic rules (velocity, amount threshold)
- CTR filing for > ₹10L cash transactions
- RBI transaction reporting integration

### Infrastructure

- 2-3 app pods behind ALB
- Postgres Multi-AZ (existing)
- Redis Cluster (3 nodes)
- Kafka MSK (3 brokers)
- CloudWatch: basic alerting

### Team

- 6 backend engineers
- 1 DevOps
- 1 compliance officer (full-time now)
- 2 QA

### Risks

- NPCI integration timeline: NEFT/RTGS/UPI each requires separate certification
- Payment rail failures: NEFT batch failures, RTGS downtime
- Fraud: first-time fraud attacks on new payment rails

### Success Criteria

- NEFT/RTGS/IMPS/UPI all live, 99.9% success rate, AML screening for all transactions

---

## Phase 2 — V2: Maker-Checker + Compliance (Months 11-18)

### Goal

Full maker-checker workflow. Advanced AML. Audit system complete. Additional account products.

### Features Added

- Full maker-checker for all significant transactions (configurable thresholds)
- Fixed Deposits (FD) product
- Recurring Deposits (RD) product
- EOD batch processing (interest calculation, statement generation)
- AML: advanced rule engine + ML model for STR detection
- Regulatory reporting: CTR, STR automation
- Audit log: complete, tamper-proof, hash chain
- Staff management: roles, permissions, branch hierarchy
- Branch operations module
- Compliance officer portal

### Architecture Changes

- Maker-checker module with Kafka-backed approval workflow
- Batch orchestrator: Kubernetes CronJob + leader election
- AML service: separate module (different deployment, different audit access)
- CQRS: account statement read model (Kafka consumer → read-optimized store)
- Elasticsearch: transaction search for compliance officers
- DR site: secondary RDS replica in second region

### Compliance

- Full RBI audit readiness
- DPDP Act compliance (privacy by design)
- IS Audit: annual security audit
- AML policy documentation
- Staff training records

### Infrastructure

- Kubernetes (EKS)
- Postgres partitioning (by date for transactions)
- Redis: session Redis (noeviction) + cache Redis (LRU) — separate instances
- Kafka: 16 partitions on transaction topics
- Prometheus + Grafana + Jaeger (monitoring stack)
- DR site: second AZ, second RDS read replica

### Team

- 12 backend engineers
- 2 SRE
- 2 compliance officers
- 3 QA
- 1 Security engineer

### Success Criteria

- Full maker-checker live, all transactions audited, AML screening 100%, pass RBI audit

---

## Phase 3 — V3: Loans + Wealth (Months 19-30)

### Goal

Complete retail banking product suite. Become primary bank for customers.

### Features Added

- Personal loans
- Home loans (basic)
- Auto loans
- Loan origination with credit scoring (CIBIL integration)
- Loan disbursement and EMI management
- NPA (Non-Performing Asset) management
- Mutual fund investments (third-party integration)
- Insurance (third-party integration)
- Priority banking for high-value customers
- Corporate banking (basic: current accounts, bulk payments)
- Open Banking APIs (RBI mandated account aggregator integration)

### Architecture Changes

- Loan Core: separate module (own DB for loan book)
- Credit decisioning: separate service (ML-based, audit required)
- Collections module (for NPA accounts)
- Account aggregator integration (NBFC-AA framework)
- Multi-core: savings DB + loan DB + investment DB
- Data warehouse: ClickHouse for product analytics + regulatory reporting
- Real-time data sync: Kafka → ClickHouse

### Compliance

- NBFC license (or partnership) for lending
- SEBI compliance for investment products
- RBI credit reporting (CIBIL/Experian/Equifax)
- Priority Sector Lending (PSL) tracking and reporting

### Team

- 25 engineers
- 5 SRE
- 4 compliance
- 1 CISO
- 1 Data engineer

---

## Architecture Evolution Summary

```
Phase 0: Accounts + Ledger (monolith, Postgres)
Phase 1: + Payment Rails (Kafka, outbox, NEFT/RTGS/UPI)
Phase 2: + Maker-Checker + AML + Full Audit (batch, CQRS, compliance tooling)
Phase 3: + Loans + Investments + Corporate (multi-core, ML, data warehouse)
```

---

## Non-Negotiables (Never Skip)

| Item | Why Non-Negotiable |
|---|---|
| Double-entry ledger from day 1 | Retrofitting = months of migration |
| Integer amounts (paise) from day 1 | Float precision errors in existing data = unfixable |
| Postgres Multi-AZ from day 1 | Financial data; single-node failure = data loss |
| Audit log from day 1 | Regulators may ask for history from start |
| Idempotency on transfers | Retrofitting = risky migration on live system |
| KYC compliance from day 1 | RBI can suspend operations for KYC violations |

---

## Interview Discussion Points

- **"Where do you start as first engineer?"** → Ledger and account model, get double-entry right, Postgres Multi-AZ, KYC compliance. Not features, not UI — data model correctness first.
- **"When do you add Kafka?"** → Phase 1: when you need reliable fan-out (notification + AML + audit from same transaction event). Before Kafka: synchronous direct calls are fragile and hard to audit.
- **"What's the biggest risk in Phase 2?"** → EOD batch correctness for interest calculation. One wrong formula = wrong balance for millions of accounts = regulatory action. Test with verified expected values before running on live accounts.
- **"How do you justify modular monolith vs microservices for banking?"** → Banking needs strong consistency for transfers. Single DB transaction for transfer = simple and correct. Distributed transactions across microservices = Saga complexity + harder regulatory audit. Monolith until scale forces extraction.
