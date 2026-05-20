# 16 — Advanced Improvements: Loan Origination & Servicing System

## Objective

Define advanced capabilities beyond V3 baseline: alternative credit scoring, Open Banking, Temporal workflow engine, BNPL at scale, and portfolio securitization. These topics demonstrate depth in financial domain engineering.

---

## Advanced Credit Decision Architecture

### Feature Store for Real-Time Scoring

Bureau pull is slow (2-5 seconds) and expensive. Alternative: pre-computed feature store.

**Features pre-computed nightly:**
- Borrower's existing EMI obligations (from credit bureau)
- Historical repayment behavior (internal — past loans)
- Digital behavior signals (login frequency, app engagement — with consent)
- Geographic risk score (based on pin code default rates)

At application time:
- Features loaded from feature store (Redis lookup, < 10ms)
- ML model scores in < 50ms
- Bureau pull only for final verification (not for initial screening)

**Result:** Auto-decision in < 500ms for 70% of applications. Bureau pull only for the remaining 30% (borderline cases, new-to-credit borrowers).

**Feature store tech:** Redis Hash per borrower (small, fast) for real-time features. PostgreSQL for historical features. Feast (open-source feature store) for management.

### Champion-Challenger Model Deployment

Two credit models run simultaneously:
- **Champion:** Current production model (90% of traffic)
- **Challenger:** New model under evaluation (10% of traffic)

A/B routing via feature flag. Track: approval rate, 90-DPD rate by cohort, portfolio quality metrics.

After 90 days: if challenger shows better risk-adjusted returns → promote to champion.

**Implementation:** Decision service routes based on hash(applicantId + salt) → deterministic, consistent assignment.

---

## Open Banking Integration (Account Aggregator)

India's AA (Account Aggregator) framework allows borrowers to share bank statement data with explicit consent.

### Benefit for Underwriting

Instead of asking borrower to manually upload 3 months' bank statements:
1. Borrower authorizes AA consent
2. AA framework pulls bank statements directly from borrower's bank
3. Statements delivered to lender in structured JSON (not PDF)
4. System analyzes: salary credits, EMI debits, spending patterns

**Result:** 5-second income verification vs 2-day document review + fraud check.

### Implementation

```
Borrower → AA Hub → Bank (FIP) → Lender (FIU)
```

AA API integration:
- POST /consent → get consent artefact
- POST /data-request with artefact → bank delivers data
- Parse structured statement → compute income, EMI obligations
- Cache per borrower per month (consent-linked)

**Regulatory:** RBI-regulated framework. Consent is revocable. Data retained only for loan decision period.

---

## Temporal Workflow Engine

### Why Temporal for V2+

Spring Batch + manual saga table works for MVP. As workflows grow complex:

| Complexity | Code-based Saga | Temporal |
|-----------|-----------------|---------|
| Linear 3-step saga | Fine | Overkill |
| Parallel bureau + KYC + bank verification | Complex — manual fork/join | Natural parallel execution |
| 90-day collections workflow with weekly check-ins | Hard — complex scheduling | Built-in timers, sleep(duration) |
| Retry with exponential backoff per step | Custom code | Built-in |
| Crash recovery | Custom saga table + recovery job | Automatic (event sourced execution) |

**Temporal disbursement workflow (pseudo-code):**

```
DisbursementWorkflow:
  1. reserveLedgerFunds() [activity]
     → on failure: stop (compensate nothing)
  2. initiateBankTransfer() [activity, idempotent]
     → on failure: compensateLedger() then stop
  3. waitForBankConfirmation(timeout=2h) [await signal or timer]
     → on timeout: queryBankStatus() [activity]
     → if still unknown after query: alert ops, pause
  4. activateLoanAccount() [activity, in-transaction]
     → on failure: alert ops (money moved, can't auto-compensate)
```

Temporal persists every step's completion. Crash + restart = workflow continues from last completed step. No custom saga table needed.

**Migration strategy:** Keep existing saga table. New loans use Temporal. Old loans finish on existing system. Decommission saga table after 6 months.

---

## BNPL at Scale

Buy Now Pay Later has different characteristics:

| Characteristic | Personal Loan | BNPL |
|----------------|--------------|------|
| Decision time | 24 hours | < 2 seconds |
| Amount | ₹50,000-10,00,000 | ₹500-50,000 |
| Disbursement | To borrower | To merchant |
| Tenure | 12-84 months | 3-12 installments |
| Volume | 50K applications/day | 5M transactions/day |

BNPL requires a completely different latency profile. Standard underwriting workflow cannot apply.

### BNPL Architecture

```
Merchant checkout → BNPL API → pre-approved limit check (Redis) → < 200ms decision
    │
    ├── Approved → disbursement to merchant + repayment plan to borrower
    └── Declined → fallback to other payment method
```

**Pre-approved limits:** Computed nightly for all registered BNPL borrowers based on behavioral model. Stored in Redis.

```
redis: bnpl_limit:{borrowerId} → {creditLimit, availableLimit}
```

At checkout: atomic Redis operation (`DECRBY available_limit`) — no DB query on critical path.

**Only difference from trading systems:** BNPL risk check is similar to the trading risk check (Redis atomic operation). BNPL is the "HFT of lending" — sub-second, high-volume.

**Volume challenge:** 5M BNPL transactions/day = 58 TPS average, 300 TPS peak. Matching-engine-style architecture not needed (1ms not required), but Redis Cluster + horizontal gateway scaling is essential.

---

## Portfolio Securitization Support

When a lender grows its portfolio, it may sell a pool of loans to a Special Purpose Vehicle (SPV) and raise capital through securitization.

### System Design Implications

- Loan accounts must be assignable to a securitization pool
- Post-assignment: EMI collections flow to SPV account, not lender's account
- Borrower experience: unchanged (same payment instructions, same lender name)
- Accounting: loan receivable moves from lender's books to SPV

**Schema addition:**

```sql
CREATE TABLE securitization_pools (
    pool_id         UUID PRIMARY KEY,
    pool_name       VARCHAR(128),
    spv_account     VARCHAR(18),
    status          VARCHAR(24) DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ DEFAULT now()
);

ALTER TABLE loan_accounts ADD COLUMN pool_id UUID REFERENCES securitization_pools(pool_id);
```

EMI collection routing: if `loan_account.pool_id IS NOT NULL` → route NACH proceeds to `securitization_pools.spv_account`.

---

## Architecture Critique (Honest Assessment)

### Current Weaknesses

| Weakness | Risk | Migration Path |
|----------|------|---------------|
| Code-based saga (V1) | Crash recovery requires ops intervention | Migrate to Temporal in V2 |
| No real-time NPA monitoring | Regulatory reporting uses T+1 data | Daily batch acceptable for regulation, not for risk management |
| Bureau as single integration | CIBIL outage = all underwriting stalled | Contract secondary bureau (Experian) as failover |
| NACH as single payment rail | NPCI downtime = EMI collection failure | Integrate UPI AutoPay as alternative |
| Monolith deployment coupling | Origination bug requires servicing downtime | Module extraction when team is ready |

### Scaling Limits

| Component | Hard Limit | Breakdown Point |
|-----------|-----------|----------------|
| PostgreSQL primary | ~5,000 writes/sec (with batching) | 5M active loans with daily updates |
| NACH batch window | 4 hours | 1M+ EMIs requires parallel file generation |
| Underwriting throughput | Bureau rate limit (10 RPS per key) | 50K applications/day (current limit) |
| Redis loan summary cache | ~1M keys (~500 MB) | 10M active loans |

### Operational Burdens

- **Monthly EMI batch:** ops team on-call 5-10 AM on 1st of every month — high-stress window
- **Bureau integration maintenance:** CIBIL changes API schema annually (undocumented) — ACL mitigates but still requires updates
- **Regulatory reporting:** RBI changes reporting format annually — schema versioning required
- **NACH file format:** NPCI changes format periodically — strict versioning and backward compatibility testing required

### What a Staff Interviewer Would Challenge

1. **"How do you handle a borrower who takes a loan in both their personal name and their company name (same PAN)?"**
   - Answer: PAN-based deduplication catches this in pre-screening. Same PAN → aggregate obligations across all accounts. DTI check uses combined obligations.

2. **"Your saga writes to PostgreSQL before publishing to Kafka. What if PostgreSQL crashes after outbox insert but before your consumer picks it up?"**
   - Answer: Outbox consumer (Debezium CDC or polling) retries from last committed position. Event is in PostgreSQL → eventually published to Kafka. No loss. Zero window of inconsistency because PostgreSQL is the source of truth.

3. **"What happens to your amortization schedule on a floating rate loan when the repo rate changes?"**
   - Answer: Two options: (1) Recalculate EMI (new EMI amount, same tenure), or (2) Keep EMI same, change tenure. Most India home loans use option 2 (tenure flex). System runs a batch job on rate change date, regenerates amortization schedules for all affected loans, sends notification to borrowers. This is a complex operation that must be atomic per loan (old schedule → new schedule transaction).

4. **"What's your data model for a co-applicant (joint loan)?"**
   - Answer: `loan_applications` has a `co_applicant_id` (nullable). Credit evaluation uses combined income of both applicants. Both applicants undergo KYC. Maker-checker decision covers both. Liability is joint — both are equally responsible. EMI can be debited from either's NACH mandate (primary + fallback).
