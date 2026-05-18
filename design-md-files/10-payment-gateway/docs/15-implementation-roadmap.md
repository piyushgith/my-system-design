# 15 — Implementation Roadmap: Payment Gateway / Wallet System

---

## Objective

Define phased implementation from MVP wallet to full payment gateway — with clear scope, architecture evolution, compliance milestones, and team scaling at each phase.

---

## Phase 0 — MVP: Basic Wallet (Weeks 1-8)

### Goal

Prove the wallet concept. Users can top up, transfer, and check balance. No cards, no external payments yet.

### Features

- User registration with KYC-lite (phone + OTP)
- Wallet top-up via bank transfer (NEFT/IMPS)
- Peer-to-peer wallet transfer
- Balance check
- Transaction history
- Admin panel: view wallets, flag accounts

### Architecture

- Spring Boot monolith
- Postgres: wallets, transactions, users
- Redis: session + OTP rate limiting
- No Kafka (synchronous only)
- Double-entry ledger from day 1 (non-negotiable — retrofitting is nightmare)

### API

- POST /wallets/topup
- POST /wallets/transfer
- GET /wallets/{id}/balance
- GET /wallets/{id}/transactions

### Compliance

- Prepaid Payment Instrument (PPI) license from RBI (required before going live)
- KYC via Aadhaar OTP / PAN (CKYC integration)
- AML: basic transaction monitoring (amount thresholds)
- PPI limits: ₹10,000/month for minimum KYC; ₹1,00,000/month for full KYC

### Infrastructure

- Single EC2 or ECS task
- RDS Postgres (Multi-AZ from day 1 — financial data)
- No Redis cluster yet (single node)

### Team

- 2 backend engineers
- 1 compliance + legal
- 1 QA

### Risks

- RBI license approval timeline (3-6 months) — start process in parallel with development
- KYC provider integration complexity
- Double-entry bugs: test exhaustively before any real money

### Success Criteria

- 1,000 wallets, P2P transfer works, zero ledger imbalances, pass RBI audit

---

## Phase 1 — V1: Payment Integration (Months 3-6)

### Goal

Users can pay merchants. Integrate with card networks (via gateway) and UPI.

### Features Added

- UPI payment acceptance (via NPCI API)
- Card payment acceptance (Stripe/Razorpay integration)
- Merchant onboarding (KYC, bank account verification)
- Payment to merchant (cart → pay → confirmation)
- Refund processing
- Merchant dashboard (basic: transactions, balance, payouts)
- Settlement: T+1 to merchant bank account
- Email + SMS notifications
- Fraud: velocity rules (basic)

### Architecture Changes

- Modular: payment module, wallet module, merchant module, settlement module
- Outbox pattern (critical: payment events must not be lost)
- Kafka introduced: `payments.payment.*` topics
- Idempotency keys: required on all payment APIs from day 1
- Webhook delivery to merchants
- PGBouncer: DB connection pooling

### Compliance

- Payment Aggregator (PA) license from RBI (if collecting payments for merchants)
- Full KYC for all merchants
- PCI DSS: start with SAQ A (card data via Stripe iframe)
- Transaction reporting to RBI (monthly)
- Suspicious transaction reporting (FIU-IND)

### Infrastructure

- 2-3 app pods behind ALB
- Postgres Multi-AZ (already in place)
- Redis Cluster (3 nodes) — idempotency, rate limiting, OTP
- Kafka (MSK, 3 brokers)
- CloudWatch alerting (basic)

### Team

- 5 backend engineers
- 1 DevOps
- 1 compliance officer
- 2 QA (payments testing requires regression coverage)

### Risks

- UPI integration: NPCI onboarding process is slow (2-3 months)
- Settlement accuracy: any bug in settlement calculation = legal liability
- Merchant fraud: fake merchants cashing out funds

### Success Criteria

- ₹1 crore/month GMV, payment success rate > 99%, T+1 settlement 100%, zero double charges

---

## Phase 2 — V2: Scale and Reliability (Months 7-12)

### Goal

Handle 10x transaction volume. Multi-payment method. Better fraud. Merchant ecosystem.

### Features Added

- BNPL (Buy Now Pay Later) integration
- EMI on card payments
- Multi-currency support (for international payments)
- Advanced fraud: ML scoring (replace rule-only)
- Chargeback management flow
- Automated reconciliation (bank settlement file matching)
- Merchant API (programmatic integration)
- Bulk payments (payroll, vendor payments)
- Escrow payments (marketplace)

### Architecture Changes

- Extract Fraud Service (ML model, separate Python service)
- Extract Settlement Service (daily batch, complex logic isolated)
- Redis: balance as source of truth for real-time debit (Redis atomic DECR)
- Ledger partitioning by account_id (Postgres table partitioning)
- CQRS: transaction history reads from read model (denormalized)
- Circuit breakers: Resilience4j on all external calls
- Vault: move to dedicated tokenization service (Spreedly/own)

### Compliance

- PCI DSS SAQ D (if processing card data — full audit)
- AML: automated suspicious transaction alerts
- Cross-border: FEMA compliance for international transfers
- Data residency: all Indian user data in Indian data centers (RBI mandate)

### Infrastructure

- Kubernetes (EKS)
- Redis Cluster (6 nodes: 3 master + 3 replica)
- Kafka: 16 partitions on payment topics
- Elasticsearch: transaction search for merchants
- PCI-compliant network segmentation (CDE isolation)
- Prometheus + Grafana + Jaeger

### Team

- 10 backend engineers
- 1 ML engineer (fraud model)
- 2 SRE
- 2 compliance
- 1 security engineer

### Risks

- ML fraud model false positives: blocking legitimate payments
- Cross-currency FX risk: holding multi-currency balances
- Settlement complexity: multi-currency, multi-country, multiple banks

### Success Criteria

- ₹100 crore/month GMV, 2,000 TPS, fraud rate < 0.1%, chargeback rate < 0.05%

---

## Phase 3 — V3: Full Gateway at Scale (Year 2)

### Goal

Compete with Razorpay/Paytm. Full payment ecosystem. Open API for third-party integration.

### Features Added

- White-label payment gateway (B2B SaaS model)
- Payment links (no-code payment collection)
- Subscription billing (recurring payments)
- Smart routing: route payment to optimal gateway (success rate optimization)
- Multiple acquiring banks (redundancy + cost optimization)
- Real-time dashboards for merchants
- Open API: full REST API for developers
- Marketplace payments: split payments, held funds, seller payouts
- International payouts (to global bank accounts)

### Architecture Changes

- Smart routing engine: choose gateway based on payment method, amount, historical success rate
- Multiple gateway adapters (Stripe, Razorpay, PayU, HDFC gateway) — abstraction layer
- Separate acquiring bank connections (VISA/MC direct connection — requires bank-level licenses)
- Multi-region active-passive deployment
- Data warehouse: ClickHouse for merchant analytics
- Own tokenization vault (HSM-backed)

### Compliance

- Own card network acquiring license (VISA/MC member bank partnership)
- Cross-border PA license
- SOC 2 Type II certification (for B2B customers)

### Infrastructure

- Multi-region (2 AWS regions: Mumbai + Hyderabad)
- 50+ Kubernetes pods
- Dedicated HSM (AWS CloudHSM)
- Advanced monitoring: custom financial metrics + synthetic monitoring

### Team

- 25 engineers across payments, fraud, platform, compliance
- Dedicated SRE team (5 engineers)
- Security team (2 engineers)
- Legal + compliance (3 people)

---

## Architecture Evolution Summary

```
Phase 0 → Phase 1 → Phase 2 → Phase 3
Wallet → + Payment Gateway → + Fraud + Scale → + Multi-gateway + International
Monolith → Modular → Services → Multi-region
Postgres → + Kafka → + ML Fraud → + ClickHouse + HSM
Manual → CI/CD → Kubernetes → Multi-region K8s
```

---

## Non-Negotiables From Day 1

These must be correct from the first line of code. Retrofitting is catastrophic:

1. **Double-entry ledger** — every debit has a credit; SUM invariant always holds
2. **Idempotency on all write APIs** — no double charges ever
3. **Integer amounts (cents/paise)** — never float for money
4. **Postgres Multi-AZ** — financial data cannot afford single-node failure
5. **Audit log from day 1** — compliance needs history from the beginning
6. **Outbox pattern** — events must not be lost when app crashes

Getting these wrong costs 10x more to fix than getting them right from the start.

---

## Interview Discussion Points

- **"When do you add Kafka?"** → Phase 1: when you need reliable fan-out (notification + settlement + analytics on same payment event). Before Kafka: direct service calls, which are fragile.
- **"How do you handle the RBI license requirement?"** → Start application process on day 1 of development; build MVP in sandbox mode; timeline risk is regulatory, not technical
- **"What's your MVP scope cut?"** → Wallet-to-wallet only, no card processing. Simpler compliance (PPI vs PA license). Card payments in Phase 1.
- **"Why is double-entry non-negotiable from day 1?"** → Retrofitting double-entry onto existing ledger requires migrating all historical data, proving invariants hold, regulatory audit of migration. Cost: 6-12 months. Implementing from day 1: 2 weeks.
