# 16 — Advanced Improvements: Payment Gateway / Wallet System

---

## Objective

Define advanced capabilities that differentiate a payment platform at scale — covering smart routing, ML fraud, real-time risk, own acquiring, and architectural refinements that move from functional to competitive.

---

## 1. Smart Payment Routing

### Problem

Single payment gateway has: outages, per-method success rate variance, geographic limitations, and fixed cost per transaction.

### Advanced Solution: Routing Engine

```
Payment request → Routing Engine
  Inputs:
    - Payment method (card brand, UPI, wallet)
    - Amount
    - User location
    - Merchant category
    - Time of day
    - Historical success rate per gateway per method (last 1h)
    
  Routing logic:
    - ML model: predict success probability per gateway
    - Waterfall: try Gateway A; if fails → Gateway B
    - Cost optimization: route to cheapest gateway with acceptable success rate
    
  Output: ranked list of gateways to try
```

**Example**: 
- Visa card: Stripe (92% success rate, last 1h) → Razorpay (89%) → PayU (85%)
- Amex card: Stripe only (others don't support Amex)
- UPI: Razorpay (98%) → Cashfree (97%)

**Impact**: 2-3% improvement in payment success rate = significant revenue at scale.

---

## 2. Real-Time Risk Engine

### Current State (Phase 2)

ML fraud scoring: single model, batch features, scores in 100-200ms.

### Advanced Improvement: Streaming Risk Platform

```
Payment event → Kafka → Risk processors (Flink / Kafka Streams)
  
Processor 1: Velocity aggregation
  - User: transactions in last 1min, 5min, 1h, 24h
  - Card: distinct users using this card
  - Merchant: spike in declined transactions
  → Real-time Flink windows (tumbling + sliding)
  → Store in Redis: risk signals
  
Processor 2: Graph fraud detection
  - Build graph: users → cards → devices → IPs → merchants
  - Detect: fraud rings (multiple accounts, same device/IP)
  → Graph DB (Neptune/Neo4j) for relationship queries
  
Processor 3: Behavioral biometrics
  - Typing speed, mouse movement patterns during checkout
  - Deviation from historical baseline = risk signal

All signals → Risk API → Score in < 50ms
```

This is what Stripe Radar and Sift do at scale. Takes 12-18 months to build and train.

---

## 3. Own Card Acquiring

### Current: Payment Gateway Intermediary

Our platform → Razorpay/Stripe → Card network → Issuing bank

Cost: 1.5-2.5% per transaction to gateway.

### Advanced: Direct Acquiring

Our platform → Card network (Visa/MC) → Issuing bank

Cost: 0.15-0.3% interchange fee (network fee) — save 1-2% per transaction.

**Requirements**:
- Partnership with a sponsor bank (holds the acquiring license)
- PCI DSS compliance (already required)
- Visa/Mastercard certification (2-3 year process)
- Reserve capital (card network mandated)
- 24/7 fraud monitoring (chargeback disputes)

**When justified**: Processing > ₹1,000 crore/month. At ₹1,000 crore/month × 1.5% fee savings = ₹15 crore/month savings.

---

## 4. Event Sourcing for Ledger

### Current State

Ledger as mutable rows: current balance updated in place. Transaction history as separate table.

### Advanced: Immutable Event Ledger

```
ledger_events (append-only):
  eventId       UUID
  accountId     UUID
  eventType     DEBIT | CREDIT
  amount        BIGINT (paise)
  currency      VARCHAR(3)
  correlationId UUID (paymentId, transferId)
  description   TEXT
  occurredAt    TIMESTAMPTZ
  createdAt     TIMESTAMPTZ  ← insertion time (immutable)
  
Current balance = SUM(credits) - SUM(debits) for accountId
```

**Benefits**:
- Complete audit trail: every balance change recorded forever
- Temporal queries: balance at any point in time
- Replay: rebuild balance from events (disaster recovery)
- Regulatory: immutable record of all financial events

**Cost**:
- Balance queries require aggregation (optimize with materialized balance + event replay on top)
- Storage grows forever (Postgres partitioning by month + archival to S3)

**When to adopt**: Phase 2 or later; requires migration of existing ledger data which is risky.

---

## 5. Reconciliation Automation

### Current State

Manual reconciliation: finance team reviews discrepancy report daily.

### Advanced: Automated Reconciliation System

```
Sources:
  Internal ledger → export daily transaction file
  Bank settlement file → SFTP download at 2 AM
  Card network file → per scheme (Visa, Mastercard) at different times
  UPI settlement → NPCI file
  
Reconciliation engine:
  Match: on transaction reference ID
  Classify:
    - MATCHED: amounts agree → auto-close
    - TIMING_DIFFERENCE: in one file only → wait T+2, re-check
    - AMOUNT_MISMATCH: same ID, different amount → P1 alert
    - MYSTERY_ENTRY: in bank file, not in our DB → P1 alert
    
Auto-resolution:
  - TIMING_DIFFERENCE older than T+3: escalate to finance
  - All MATCHED: mark reconciled, archive
  - AMOUNT_MISMATCH: auto-file dispute with bank
```

At scale: 1M transactions/day × manual review impossible. Automation is mandatory by Phase 3.

---

## 6. Subscription Billing Engine

### Problem

Merchants need to charge customers periodically (SaaS, streaming, insurance).

### Advanced Feature

```
Subscription lifecycle:
  ACTIVE → charge on schedule → success → stay ACTIVE
          → failure → RETRY (3 attempts, exponential backoff)
          → 3 failures → PAST_DUE
          → user updates payment method → resume
          → max retries exceeded → CANCELLED → notify merchant

Smart retry timing:
  - Retry on salary credit dates (1st, 5th, 30th of month)
  - Retry early morning (funds more likely available)
  - Card network "account updater" API: auto-update expired card details

Dunning management:
  - Email sequence: day 1, day 3, day 7 before cancellation
  - In-app notification
  - Merchant webhook on status change
```

**Revenue impact**: failed subscription recovery 5-15% of churned revenue recovered via smart retry.

---

## 7. UPI Next-Generation Integration

### Current: Basic UPI Collect/Pay

### Advanced: UPI 2.0 + UPI Lite + Credit on UPI

```
UPI 2.0 features:
  - Linked overdraft accounts (debit from OD, not savings)
  - One-time mandate (auto-debit with user pre-approval)
  - Invoice in request (rich payment context)
  
UPI Lite (offline payments):
  - Small-value payments (< ₹500) without internet
  - Balance stored on device
  - Reconcile when online
  
Credit on UPI:
  - Link credit card to UPI VPA
  - Pay via credit card through UPI interface
  - New merchant opportunity: accept credit via UPI rail
```

NPCI is pushing these aggressively. First-mover advantage for payment processors integrating them.

---

## 8. Payment Analytics Platform

### For Merchants (Revenue Opportunity)

Advanced analytics is a B2B upsell:

```
Dashboard capabilities:
  - Conversion funnel: payment initiated → success %
  - Decline reason analysis (insufficient funds vs expired card vs fraud)
  - Success rate by: geography, payment method, time of day
  - Chargeback prediction (which orders are high risk)
  - Revenue forecasting (ML-based)

Merchant actions:
  - "Enable retry": auto-retry failed payments
  - "Enable smart routing": route to higher-success gateway
  - Fraud threshold customization (higher threshold = more approvals, more risk)
```

Analytics is a competitive differentiator (Stripe Sigma vs basic Razorpay dashboard).

---

## 9. Architecture Self-Critique

### Weaknesses

| Weakness | Risk | Mitigation |
|---|---|---|
| Single Postgres for high-volume ledger | Write throughput ceiling | Partition by account_id; eventually shard |
| Redis balance drift | Financial inconsistency | 5-minute reconciliation; Redis AOF persistence |
| Settlement depends on bank SFTP | Single point of coupling | Multiple file delivery channels + API fallback |
| Fraud model drift | Model degrades over time | Weekly retraining; champion-challenger testing |
| Manual chargeback handling | Doesn't scale | Automated evidence collection and filing |

### Scaling Limits

| Component | Limit | When Needed |
|---|---|---|
| Postgres single primary | ~10K TPS writes | > 1M transactions/day |
| Redis single cluster | ~300 GB wallet data | > 50M wallets |
| Kafka single cluster | ~1M msg/sec | > 100M transactions/day |
| Single card gateway | Gateway rate limits | > 10K TPS sustained |

### Tech Debt Risks

1. **Retrofitting double-entry**: if skipped in Phase 0, migration is a multi-quarter project with regulatory audit
2. **Integer vs float amounts**: one float calculation corrupts ledger irreversibly
3. **Missing idempotency early**: adding idempotency to high-traffic endpoint requires careful state migration
4. **Sync-only payment flow**: migrating to async requires careful state machine migration; in-flight payments during migration are dangerous

### FAANG Interviewer Challenges

- *"How do you handle chargebacks at 1M transactions/day?"* → Automated evidence collection (order data, delivery proof, fraud signals) + machine classification (legitimate dispute vs friendly fraud) + auto-filing with card network
- *"Your double-entry invariant check runs on Postgres — doesn't that scale poorly?"* → Yes; at scale, move to streaming: Kafka Streams computes running debit/credit totals per window; compare in real-time vs batch
- *"How do you handle regulatory reporting across 10 countries?"* → Compliance-as-code: per-country rules engine; separate data pipeline per jurisdiction; local data residency; country-specific audit exports
- *"What's your strategy for the next 5 years in payments?"* → Payments infrastructure becomes a platform; merchant analytics + financing + insurance + embedded finance are the actual margin; raw payment processing is commoditized
