# 00 — Requirements Analysis: Payment Gateway / Wallet System

## Objective

Define the functional and non-functional requirements for a production-grade Payment Gateway and Wallet system. Establish scale targets, traffic assumptions, storage estimates, and the architectural constraints that drive every downstream decision.

---

## 1. Problem Statement

Build a system that:
1. Accepts payments from end-users via multiple payment instruments (cards, UPI, bank transfer, wallets).
2. Routes payments through acquiring banks and payment networks (Visa, Mastercard, NPCI).
3. Maintains a digital wallet per user with top-up, peer-to-peer transfer, and withdrawal capabilities.
4. Settles merchant accounts on a defined schedule.
5. Detects fraud in real-time and near-real-time.
6. Remains PCI DSS Level 1 compliant.

---

## 2. Functional Requirements

### Payment Processing
- Accept charge requests for a given amount, currency, and payment method.
- Support payment methods: credit card, debit card, UPI, net banking, wallet balance.
- Return synchronous response for fast payment paths (card auth < 2 s).
- Handle asynchronous payment paths (net banking, UPI collect) via webhook callbacks.
- Support pre-authorization and capture (hotel/rental use cases).
- Support partial captures and partial refunds.
- Support 3D Secure (3DS2) authentication flow.

### Wallet
- User wallet creation tied to KYC status.
- Top-up wallet via card, UPI, or bank transfer.
- Peer-to-peer transfer between wallets.
- Withdrawal to linked bank account (NEFT/IMPS/UPI).
- View transaction history with filters (date, type, status).
- Multi-currency wallet (INR primary, USD/EUR secondary).

### Refunds & Chargebacks
- Full and partial refund initiation by merchant.
- Chargeback intake from card networks with evidence upload.
- Chargeback dispute workflow (merchant response, bank arbitration, resolution).
- Automatic refund to original payment instrument.

### Settlement & Reconciliation
- Daily settlement cycle: batch merchant payouts after T+1 or T+2.
- Reconciliation report: match gateway records against acquirer statements.
- Discrepancy flagging and manual resolution queue.

### Fraud Detection
- Real-time rule engine (velocity checks, blacklists, device fingerprint).
- Near-real-time ML scoring service (featurized from event stream).
- Manual review queue for borderline transactions.
- Feedback loop: chargeback outcomes retrain ML model.

### Merchant Management
- Merchant onboarding with KYB (Know Your Business).
- API key and webhook management per merchant.
- Dashboard: transaction history, settlement status, dispute status.

---

## 3. Non-Functional Requirements

| Attribute | Target |
|---|---|
| Payment API latency (p99) | < 500 ms for card auth (excluding bank RTT) |
| Wallet transfer latency (p99) | < 200 ms |
| Availability | 99.99% (< 52 min/year downtime) |
| Consistency | Strong consistency for ledger entries; eventual for analytics |
| Idempotency | All payment APIs must be safe to retry |
| Durability | Zero payment data loss — double-entry ledger with audit trail |
| Throughput | 5,000 TPS peak (Diwali/Black Friday spike) |
| Fraud detection latency | < 50 ms rule engine inline; < 5 s ML model async |
| Compliance | PCI DSS Level 1, RBI Payment Aggregator guidelines |
| Data retention | 7 years for payment records (regulatory requirement) |

---

## 4. Assumptions

- The system operates in India-first context (INR primary, UPI/cards dominant), with USD/EUR wallets for international merchants.
- Merchants integrate via REST API + webhooks; not a hosted checkout page (though that can be layer-2).
- The system is a Payment Aggregator, not a full acquiring bank — it routes to partner acquirers.
- KYC/KYB is provided by a third-party service (e.g., Aadhaar-based eKYC, CKYC); this system consumes its results.
- Card tokenization uses the network tokenization standard (Visa Token Service / Mastercard MDES) — raw PANs never touch our servers (redirect to a PCI-certified vault).
- 3DS authentication is handled by an ACS (Access Control Server) operated by the card network; we initiate and receive results.

---

## 5. Constraints

- PCI DSS prohibits storing raw CVV or full PAN in application databases.
- RBI mandates that payment data of Indian cardholders must reside in India (data localization).
- Wallet operations are regulated under the RBI PPI (Prepaid Payment Instrument) framework — KYC limits apply (₹10,000 without KYC, ₹2,00,000 with full KYC).
- Settlement must be auditable: every rupee must trace from merchant account to acquirer settlement.
- System must support idempotency keys to handle network retries from merchants without double-charging.

---

## 6. Scale Estimation

### Traffic Assumptions

| Scenario | RPS |
|---|---|
| Baseline (daytime) | 500 RPS |
| Business hours peak | 1,500 RPS |
| Sale event peak (Diwali/BBD) | 5,000 RPS |
| Wallet P2P peak (festivals) | 2,000 RPS |

Breakdown of payment creation requests:
- 60% card payments (40% domestic, 20% international)
- 25% UPI
- 10% wallet balance
- 5% net banking

### Storage Estimation

**Transactions:**
- Average transaction record: ~2 KB (including metadata, audit fields)
- 1 million transactions/day → 2 GB/day raw
- Ledger entries: each transaction creates 2–4 ledger rows → 4–8 GB/day
- 7-year retention: ~20 TB for transactions + ledger (without compression)
- With 3x compression (PostgreSQL): ~7 TB

**Events (Kafka):**
- Payment events: ~2 KB each, 1M/day → 2 GB/day
- 30-day retention on Kafka: ~60 GB

**Fraud features (Redis):**
- Velocity counters per user/card/IP: ~500 bytes each, 10M active keys → 5 GB in Redis

**Audit logs (ELK):**
- 5 KB per request × 1M requests/day → 5 GB/day
- 90-day hot storage: ~450 GB; cold archive thereafter

### Capacity Planning

| Component | Baseline | Peak |
|---|---|---|
| Payment API pods | 4 × 4-core | 16 × 4-core (HPA) |
| PostgreSQL primary | 16-core, 64 GB RAM | 32-core, 128 GB RAM |
| PostgreSQL read replicas | 2 | 4 (auto-scale lag) |
| Redis cluster | 3-node, 16 GB each | 6-node (slot rebalance) |
| Kafka brokers | 3 | 6 |
| Fraud engine pods | 2 | 8 |

---

## 7. Read / Write Patterns

| Operation | Pattern | Notes |
|---|---|---|
| Payment creation | Write-heavy | Transactional, ACID required |
| Payment status check | Read-heavy | High frequency (polling), cacheable |
| Ledger entries | Write-heavy | Append-only, never update |
| Transaction history | Read-heavy | Paginated, index on user + timestamp |
| Fraud feature lookup | Read-heavy | Sub-millisecond required → Redis |
| Settlement batch | Read-heavy | Nightly batch, large sequential scan |
| Merchant dashboard | Read-heavy | Acceptable 30 s staleness → read replica |

---

## 8. Latency Expectations

| Flow | SLO (p99) |
|---|---|
| Card payment (auth + capture) | < 800 ms end-to-end (500 ms gateway + 300 ms bank) |
| UPI collect initiation | < 300 ms (async; status via webhook) |
| Wallet transfer | < 200 ms |
| Payment status query | < 50 ms (Redis cache hit) |
| Refund initiation | < 500 ms |
| Fraud rule check (inline) | < 30 ms |

---

## 9. Availability Targets

- Payment API: 99.99% (four nines) → < 52 min/year.
- Wallet API: 99.99%.
- Merchant dashboard: 99.9% (three nines acceptable; non-payment-critical).
- Settlement service: 99.9% (batch; not real-time critical, but failures must alert).
- Fraud engine: 99.9% (degrade gracefully: allow-on-timeout with elevated monitoring).

**Disaster Recovery:**
- RPO (Recovery Point Objective): 0 seconds for ledger (synchronous replication).
- RTO (Recovery Time Objective): < 5 minutes (automated failover via RDS Multi-AZ or Patroni).

---

## 10. Interview-Level Discussion Points

- **Why is idempotency non-negotiable here?** A merchant retrying a timed-out request without idempotency would cause double charges — a catastrophic customer trust issue and regulatory violation.
- **How do you estimate the ledger table size for 7 years?** Walk through: TPS → daily rows → row size → compression ratios → storage tier costs.
- **Why separate wallet from payment gateway at the domain level?** They have different regulatory frameworks (PPI vs. Payment Aggregator), different consistency models, and different failure modes.
- **What does 99.99% actually cost operationally?** Multi-region active-active or active-passive, automated failover, runbook coverage for every failure mode, regular DR drills.
- **Where would a startup differ?** A startup would start with a single PostgreSQL instance, Stripe as the acquirer (no direct network access), no ML fraud — rule-based only, no 3DS implementation (delegate to Stripe). Complexity is added incrementally as volume and compliance requirements demand.
