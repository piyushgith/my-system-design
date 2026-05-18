# 16 — Advanced Improvements: Banking Core System

---

## Objective

Define advanced capabilities that move a banking core from functional to competitive — covering event sourcing for immutable history, real-time AML, open banking, ML credit decisioning, and the architectural refinements that distinguish tier-1 banks from challengers.

---

## 1. Event Sourcing for Financial Ledger

### Current State

Ledger as mutable rows: balance updated in place, entries appended but balance is a separate column.

### Advanced: True Immutable Event Ledger

```
No stored balance. Balance = computation from events.

ledger_events (append-only, no UPDATE ever):
  eventId       UUID
  accountId     UUID
  eventType     CREDIT | DEBIT
  amount        BIGINT (paise)
  currency      VARCHAR(3)
  reference     TEXT (NEFT ref, EMI ref, etc.)
  description   TEXT
  occurredAt    TIMESTAMPTZ   ← when it happened
  recordedAt    TIMESTAMPTZ   ← when recorded in system
  
Current balance:
  SELECT SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END)
  FROM ledger_events WHERE account_id = ?
  
Balance at any point in time (temporal query):
  WHERE occurred_at <= '2026-01-01T00:00:00'
```

**Benefits**:
- True temporal query: balance at any date in history
- Regulatory audit: immutable evidence of every financial movement
- Replay: rebuild balance from scratch (disaster recovery validation)
- Debug: reproduce any historical state exactly

**Performance optimization**:
- Materialized balance: store current balance as derived value, updated on each event
- Full event source + materialized snapshot: snapshot every N days, replay events from snapshot to present
- This gives O(1) balance reads with full audit capability

**When to adopt**: if facing regulatory pressure for immutable transaction history, or if temporal queries are business requirements (e.g., "what was this account's balance on Dec 31 for tax purposes?").

---

## 2. Real-Time AML Streaming

### Current State

AML screening: batch consumption from Kafka, processes transactions within 1 hour.

### Advanced: Streaming AML with Flink

```
Kafka: banking.transaction.completed
    ↓
Apache Flink (streaming processor):
  
  Rule 1: Velocity monitoring (sliding window)
    - Window: 24 hours per account
    - Alert: total outflow > ₹10L in 24h
    - Real-time aggregation via Flink state store

  Rule 2: Structuring detection
    - Multiple transactions just below CTR threshold (₹10L)
    - Pattern: 3+ transactions of ₹9-9.9L within 24h
    - Classic "smurfing" detection

  Rule 3: Network analysis (graph-based)
    - Money moves: A → B → C → A (round-trip)
    - Within 48h: indicates money laundering cycle
    - Graph state maintained in Flink

  Rule 4: Geographic anomaly
    - Transaction initiated from country X but account registered in India
    - Or: UPI transaction from unusual location

  Alert → banking.aml.alert-generated → Compliance officer notified within 1 minute
```

**Current vs Advanced**:
| Aspect | Batch AML | Streaming AML |
|---|---|---|
| Detection latency | 1 hour | < 1 minute |
| Window computation | Fixed batch | Sliding window (Flink) |
| Transaction block | After detection | Real-time (can block before settlement) |
| Infrastructure | Kafka consumers | Kafka + Flink cluster |
| Complexity | Low | High |

Streaming AML allows blocking suspicious transactions before NEFT settlement (within-hour batch). This is the difference between preventing money laundering and reporting it afterward.

---

## 3. ML Credit Decisioning

### Current: Rule-Based Scoring

```
CIBIL score > 750: approve
CIBIL score 700-750: conditional (income proof required)
CIBIL score < 700: reject
```

### Advanced: ML-Based Credit Model

```
Features:
  - CIBIL score + CIBIL history trend (improving vs declining)
  - Income: salary credits to our account (behavioral, not stated)
  - Spending patterns: EMI burden vs income
  - Banking tenure: age of relationship with our bank
  - Digital behavior: bill payment regularity, investment patterns
  - Demographic: employment type, employer quality (startup vs PSU)
  - External: industry sector, economic indicators

Model: Gradient Boosted Tree (XGBoost)
  - Binary: approve/reject
  - Continuous: credit limit, interest rate

Output: { approve: bool, creditLimit: ?, interestRate: ?, confidenceScore: 0-1 }

Explainability requirement (RBI mandate):
  - If rejected: explain top 3 reasons in plain language
  - Reason: "Low CIBIL score (650)" + "High EMI burden (65% of income)"
  - Cannot use black-box model for regulatory purposes without explainability
```

**Model governance**:
- Champion-challenger: new model tested on 5% traffic vs champion
- Monthly retraining on fresh data
- Bias audit: ensure model doesn't discriminate by gender, religion, geography (RBI fairness requirements)
- Model risk committee approval for production deployment

---

## 4. Open Banking / Account Aggregator Framework

### RBI Account Aggregator (AA) Framework

RBI mandates that banks participate in AA ecosystem:

- Financial Information Provider (FIP): share customer data with authorized aggregators
- Financial Information User (FIU): consume customer data from other banks (with consent)

**What this enables**:
- Customer applies for loan at our bank
- With consent: we can see their salary account at HDFC, investments at Zerodha
- Better credit assessment without asking for documents
- Reduces fraud (fake salary slips)

**Technical implementation**:
```
AA Integration API (our bank as FIP):
  POST /FI/request → receive consent from aggregator
  Verify: consent signed by customer with their UIDAI key
  Return: customer's account data in AA standard format (JSON)
  Audit: every data sharing request logged

Data categories:
  - Bank statements (DEPOSIT)
  - Mutual fund holdings (MUTUAL_FUNDS)
  - Insurance policies (INSURANCE_POLICIES)
  - Tax data (ITR from CBDT) — via tax AA extension
```

---

## 5. Fraud Intelligence Network

### Consortium-Based Fraud Sharing

Individual banks see limited fraud signals. Collective intelligence is much stronger.

**Implementation**:
- Bank consortium (like FACE — Fintech Association for Consumer Empowerment)
- Share: flagged device fingerprints, fraudulent account numbers, known mule accounts
- Receive: alerts when your customer's data appears in fraud databases
- Privacy-preserving: share hashes, not raw data

**Technical**:
- Federated learning: train AML model on consortium data without sharing raw transactions
- Risk score API: query consortium database before each transaction

---

## 6. Banking as a Service (BaaS)

### Advanced Business Model

Expose banking infrastructure to fintech companies via API:

```
Fintech company → BaaS API → Our Core Banking
  
What we expose:
  - Account creation (KYC by us)
  - Fund transfers (NEFT/RTGS/IMPS/UPI)
  - Balance and statement
  - Debit card issuance
  - Lending (co-branded)
  
Revenue model:
  - Per API call pricing
  - Per account per month
  - Revenue share on lending products
  
Regulatory:
  - Fintech is Banking Correspondent of our bank
  - We hold the banking license; they hold the customer relationship
  - Compliance responsibility: shared (contractually defined)
```

**Architecture implication**: BaaS API is a separate gateway in front of core banking. Tenant isolation (multi-tenancy) from day 1 in BaaS layer.

---

## 7. Core Banking Modernization (Strangler Fig)

### Problem

Many banks run on 30-year-old COBOL core banking systems (Finacle, Temenos T24, IBM System z).

### Strangler Fig Migration Pattern

```
Step 1: Create facade (our modern API)
  Modern API Gateway → adapts to legacy CBS API
  All new features built in modern system
  Legacy CBS: source of truth still

Step 2: Strangle individual accounts
  Migrate 5% of accounts to modern core
  Modern core is source of truth for these accounts
  Legacy CBS: read-only mirror for regulators

Step 3: Expand
  Migrate product by product, account by product
  Each migration: dual-write period → cutover → verify → proceed

Step 4: Decommission
  Legacy CBS: archive mode only
  Modern system: full banking stack

Timeline: 5-10 years for large bank (100M+ accounts)
Risk: this is the highest-risk technology project a bank can undertake
```

---

## 8. Architecture Self-Critique

### Weaknesses

| Weakness | Risk | Mitigation |
|---|---|---|
| Single Postgres for account + transaction | Write throughput ceiling at 5M+ accounts | Partition by account_id range |
| Batch processing in single thread | EOD overrun risk | Distribute across account ranges |
| Maker-checker event-driven complexity | Debugging workflow state is hard | Comprehensive event log + state visualization |
| AML rule engine hardcoded | Can't update without deployment | Rules in DB; compliance team configures |
| No event sourcing | Limited temporal query ability | Plan migration in Phase 3 |

### Scaling Limits

| Component | Current Limit | Next Step |
|---|---|---|
| Postgres single primary | ~10K TPS | Partition ledger by account_id |
| AML Kafka consumer | ~5,000 msg/sec | Scale to partition count (16 consumers) |
| EOD batch | 10M accounts/night | Distribute across account ranges |
| Redis session | ~50M sessions | Redis Cluster with more shards |

### Tech Debt Risks

1. **Integer amounts**: if any developer introduces float for money calculations — global corruption risk. Enforce via code review + static analysis rule
2. **Missing idempotency early**: retrofitting idempotency onto existing transfer API = risky migration with in-flight transactions
3. **Monolith grows beyond manageable size**: extract first module when module cohesion breaks down (foreign keys between modules, excessive shared code)
4. **Hardcoded AML rules**: regulatory rules change; hardcoded = deployment for every rule update; move to rules engine early

### Taking Interviewer Challenges

- *"Your double-entry check runs every 1 minute — doesn't that slow down Postgres?"* → Run against read replica; non-blocking aggregate query; at large scale, move to streaming (Kafka Streams running imbalance check in real-time)
- *"How do you handle Aadhaar OTP verification without storing Aadhaar?"* → Aadhaar OTP API (UIDAI): submit Aadhaar number, receive OTP on registered mobile, verify OTP — all via UIDAI API. We store nothing except the verified result (KYC_VERIFIED = true) and masked last 4 digits.
- *"How do you ensure the audit log is complete — what if the application crashes before writing?"* → Outbox pattern: audit log write is part of the DB transaction that records the business event. Both committed atomically. If app crashes before DB commit: nothing recorded. If crash after commit: audit log guaranteed present.
- *"What happens to pending maker-checker requests during a deployment?"* → Requests in PENDING state remain in DB. New version of app consumes the same pending requests. Idempotency on the execution step prevents double-execution during restart overlap.
