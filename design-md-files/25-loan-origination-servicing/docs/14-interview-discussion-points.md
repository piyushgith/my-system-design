# 14 — Interview Discussion Points: Loan Origination & Servicing System

## Objective

Prepare for senior and staff engineer discussions on the architecture tradeoffs specific to lending systems — long-running workflows, financial correctness, regulatory constraints, and maker-checker patterns.

---

## Core Concept Questions

### "Walk me through how a loan application gets approved."

**Strong answer:**
1. Borrower submits application (DRAFT → SUBMITTED) with documents
2. Application Service validates and publishes `LoanApplicationSubmitted` to Kafka
3. Underwriting Service consumes, triggers async bureau pull (CIBIL)
4. Bureau callback received → Underwriting evaluates DTI, bureau score against credit policy
5. If auto-approvable (high score, small amount): → APPROVED immediately
6. If above threshold: MakerCheckerTask created → maker reviews, submits decision → checker independently approves
7. On approval: LoanOffer generated → borrower accepts
8. Offer acceptance triggers Disbursement Saga

**Key points to hit:**
- Async bureau check (don't block API response on 5-second bureau call)
- Auto-approval vs maker-checker boundary (configurable threshold, not hardcoded)
- Maker-checker is enforced by the system (different actors, same-team restriction), not by process

### "How does the amortization schedule work?"

- EMI = fixed monthly payment for the loan tenure
- Each EMI = Principal Component + Interest Component
- Interest component = outstandingPrincipal × monthlyRate
- Principal component = emiAmount - interestComponent
- Closing principal = openingPrincipal - principalComponent
- Early in the loan: interest is high (large outstanding), principal is small
- Late in the loan: interest is low, principal component is high
- Total sum of all principal components = original loan amount (invariant)
- Rounding adjustment on last installment

**Interview trap:** Using floating point for money calculations. Always BigDecimal with explicit rounding (HALF_UP per banking standards).

---

## Tradeoff Questions

### "Why Modular Monolith instead of Microservices?"

**Strong answer with tradeoffs:**

For (monolith):
- 5-8 engineer team — each service would need its own CI/CD, monitoring, operational runbook
- Loan lifecycle state machine is simpler in-process (no distributed saga for every state change)
- Database transactions work across the entire loan lifecycle without 2PC
- Wrong service boundaries early → expensive to fix with Microservices; easy to refactor within monolith

Against (monolith):
- Blast radius: one bug can bring down origination and servicing together
- Deployment coupling: can't deploy EMI service independently
- Scaling coupling: can't scale only the EMI worker independently

**Decision: monolith now, extract based on evidence of need (team ownership, scaling requirement).**

### "How does your disbursement saga handle partial failures?"

**The 4 scenarios:**
1. **Ledger reservation fails:** Saga never started. No money moved. Simple rollback.
2. **Ledger reserved, bank transfer fails:** Compensate — reverse ledger reservation. Clean rollback.
3. **Bank transfer succeeds, loan activation fails:** Most dangerous. Money moved, no loan account. Compensation: create "suspense" ledger entry, alert ops team, manual intervention to activate loan. Cannot automatically reverse bank transfer (funds in borrower's account).
4. **All succeeds, confirmation event lost:** Saga timeout → query bank API directly → if confirmed, proceed with activation.

**Key insight:** Transaction boundaries don't cross system boundaries. The saga manages eventual consistency across systems that each have their own transactional guarantees.

### "What happens if CIBIL is down during a campaign with 10,000 applications/hour?"

- Applications accepted immediately (202 Accepted) — decoupled from bureau check
- Kafka queue holds `LoanApplicationSubmitted` events
- Underwriting Service keeps retrying bureau call with exponential backoff
- When CIBIL recovers, queue drains (at bureau's rate limit)
- Applications processed in order they arrived
- Borrower sees "Under Review" — no error shown to them
- Bureau cache used if same PAN pulled within 24 hours (no new charge)
- If bureau is down > 2 hours: fallback to Experian (if contracted), or hold queue with notification to borrowers

**This is the value of async workflow — the frontend never felt the bureau outage.**

---

## Distributed Systems Questions

### "How do you prevent double-charging a borrower?"

Three-layer defense:

1. **NACH debit idempotency:** NACH mandate has a unique reference per presentation. Same reference cannot be presented twice.
2. **Application-level idempotency:** `repayment_records.idempotency_key` = NPCI result file row ID. `INSERT ... ON CONFLICT DO NOTHING`.
3. **Database constraint:** `UNIQUE(idempotency_key)` on repayment_records is the final guarantee regardless of application bugs.

**You can never guarantee the idempotency key check in Redis is the last line of defense. The DB constraint is the actual guarantee.**

### "How do you handle the case where a borrower claims they paid but the system shows MISSED?"

Investigation flow:
1. Ask borrower for payment reference (bank transaction ID)
2. Query `repayment_records` by payment_reference — if found, system has it, UI may be stale
3. If not in repayment_records: query audit_log for recent events on this loan account
4. Check if NACH result file from that date was fully processed (Kafka consumer lag? Job failure?)
5. Check NACH result file in S3 for that date — find the row with borrower's mandate
6. Manual reconciliation: if bank confirms debit, create manual repayment record with ops actor, audit trail

**Key: the audit log + event history gives full reconstruction ability.**

### "How does the system ensure the maker and checker for a credit decision are different people?"

At three levels:
1. **Application level:** When assigning checker, validate `checker_id != maker_id`. Return 400 if same person.
2. **Database constraint:** `CHECK (maker_id IS NULL OR checker_id IS NULL OR maker_id != checker_id)` — enforced even if application code has a bug.
3. **Business rule:** Checker must be from a different team/hierarchy than maker. Application query: `SELECT users WHERE team_id != maker.team_id AND role = 'CHECKER'`. DB stores checker's team at time of assignment.

---

## Senior Engineer Discussion Points

### Long-Running Workflow Management: Code vs Temporal

**Code-based state machine (MVP):**
- Simple, easy to understand, no infrastructure dependency
- Difficult to manage: timeouts, retries, step-by-step recovery must be coded manually
- Breaks: long-running saga recovery after crash requires custom saga state table + recovery job
- Suitable for: < 3 steps, < 1 hour workflow

**Temporal.io (V2+):**
- Durable workflow execution — workflows survive crashes by design
- Built-in retry, timeout, compensation handling
- Visible workflow history in Temporal UI
- Code is natural Java/Go — not a DSL
- Added: Temporal server infrastructure to operate (managed cloud offering available)
- Suitable for: complex, long-running workflows (loan origination saga spans hours)

**Interview answer:** "I'd start with code + saga table for MVP. As workflow complexity grows (multi-party loans, restructuring chains, cross-border), I'd migrate to Temporal. The migration is non-disruptive — Temporal is a workflow engine over Kafka, same events."

### NPA Management and Provisioning

Banks are required by RBI to set aside provisions for NPAs:

- 0-30 DPD: Standard asset, 0% provisioning
- 31-90 DPD: Sub-standard, 15% provisioning
- 90+ DPD: Doubtful, 25-100% provisioning
- Written-off: Loss asset, 100% provisioning

**System design implication:** NPA classification must be accurate, automated (daily DPD job), and auditable. Every NPA classification creates a ledger entry for provisioning. Wrong classification = wrong financial statements.

### The Prepayment Penalty Design

When a borrower prepays early, lender loses expected interest income. Prepayment fee compensates.

**Design considerations:**
- Fee = X% of outstanding principal (industry standard: 1-3%)
- Some regulatory frameworks (India, EU) restrict prepayment penalties on floating rate loans
- System must check: is this loan eligible for penalty? (loan type, rate type, regulatory region)
- Prepayment recalculates amortization schedule: two options:
  1. **Reduce tenure** (same EMI, fewer months) — borrower saves maximum interest
  2. **Reduce EMI** (same tenure, smaller monthly payment) — borrower keeps flexibility
- System offers both options; borrower chooses

---

## Staff Engineer Discussion Points

### Event Sourcing vs Audit Log

**Common mistake:** Implementing full event sourcing for loan accounts (rebuilding current state from events).

**Reality for lending:**
- Loan account has < 360 events over its lifetime (one per month + some extras)
- Rebuilding from events: slow for queries, complex for reporting, operational burden
- Current state table + append-only audit log = simpler, equally auditable, faster queries
- Event sourcing adds value when: current state cannot be modeled as a table (complex, branching history), or replaying events is a core business requirement (e.g., regulatory replay)

**For lending:** Current state table + audit log is the right architecture.

### Regulatory Reporting and Data Lineage

RBI requires:
- Monthly portfolio reports (SMA — Special Mention Account classification)
- Quarterly NPA reports
- Annual audit trail submission

**Data lineage requirement:** Regulator asks "How did you compute this NPA figure?" → trace back through loan records → amortization schedule → repayment history → bureau data → every step auditable.

**System design:** Every event has a `correlationId`. The audit log has a full chain from application submission to current loan state. Compliance officer can reconstruct any decision using audit_log queries alone.

---

## Common Mistakes

| Mistake | Why It's Wrong |
|---------|---------------|
| Using floating point for EMI calculations | Rounding drift over 24 months = wrong final payment amount |
| Synchronous bureau call on application submit API | Bureau takes 5 seconds → 5-second API response → poor UX + timeout risk |
| Single actor maker-checker (same person) | Regulatory violation — defeats four-eyes principle |
| Relying on Redis idempotency alone (without DB constraint) | Redis TTL expiry or failure = duplicate payment possible |
| Auto-rejecting applications at maker-checker SLA breach | Revenue loss — refer to senior underwriter instead |
| Storing EMI schedule as a single JSON blob | Cannot query individual installment status; cannot update one entry efficiently |
| Writing disbursement confirmation and loan activation in separate DB transactions | Crash between the two = money sent, no loan = financial exposure |
| No timeout on disbursement saga | Bank webhook never arrives = saga stuck forever, loan never activated |

---

## "What Would Break First?" Analysis

At 10x current scale (20M active loans, 666,667 EMIs/month):

1. **EMI batch processing time:** 10x batch size → 1.3M EMIs to submit → need 500 NACH workers, 30-minute window insufficient → need distributed batch orchestration (Apache Spark)
2. **PostgreSQL write throughput:** 300 writes/sec on EMI day → 3,000 writes/sec at 10x → needs sharding or Citus (distributed PostgreSQL)
3. **NACH file size:** 13 MB → 130 MB per batch file → NPCI file size limits become an issue → split into multiple files
4. **Maker-checker queue depth:** More applications → more tasks → need intelligent routing and load balancing across underwriting teams
5. **Bureau API cost:** 10x applications → 10x bureau pulls → ₹50 per pull × 500K/day = ₹25 million/day → aggressive caching, soft pull strategy critical

**Correct answer structure:** Identify each bottleneck, quantify at what scale it breaks, propose concrete mitigation.
