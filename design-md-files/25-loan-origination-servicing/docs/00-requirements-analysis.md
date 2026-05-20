# 00 — Requirements Analysis: Loan Origination & Servicing System

## Objective

Define requirements for an end-to-end lending platform covering the full loan lifecycle: application intake → underwriting → approval → disbursement → repayment schedule → EMI collection → closure.

---

## Functional Requirements

### Origination (Application → Approval)

| # | Requirement |
|---|-------------|
| F1 | Borrower submits loan application with personal, income, and employment data |
| F2 | System validates application completeness and document upload |
| F3 | Credit policy rules applied: bureau score, DTI ratio, income verification |
| F4 | Maker-checker workflow for credit decisions above configurable threshold (e.g., > ₹10 lakh) |
| F5 | Underwriter can approve, reject, or request additional documents |
| F6 | Approved applications generate loan offer with term, rate, EMI schedule |
| F7 | Borrower accepts offer and signs digital loan agreement |
| F8 | KYC verification integrated into origination (or assumed pre-completed) |

### Disbursement

| # | Requirement |
|---|-------------|
| F9 | On acceptance + KYC pass: disburse loan amount to borrower's bank account |
| F10 | Disbursement triggers debit of lending pool ledger and credit of borrower account |
| F11 | Loan activation: create loan account, generate amortization schedule |
| F12 | Support partial disbursement for construction/project loans |

### Servicing (Post-Disbursement)

| # | Requirement |
|---|-------------|
| F13 | EMI schedule generation (equal monthly installment with principal + interest breakdown) |
| F14 | EMI collection: debit borrower's registered bank account on due date |
| F15 | Track payment status: PAID, PARTIAL, MISSED, BOUNCED |
| F16 | Prepayment: partial or full early repayment with prepayment fee calculation |
| F17 | Late payment penalties: automatic fee addition after grace period |
| F18 | NPA (Non-Performing Asset) classification after 90 days overdue |
| F19 | Loan restructuring: extend tenure, reduce EMI with maker-checker approval |
| F20 | Loan closure: full repayment or write-off workflow |

### Collections

| # | Requirement |
|---|-------------|
| F21 | Collections workflow for overdue accounts: reminder → escalation → field agent |
| F22 | Legal escalation workflow for NPA accounts |

---

## Non-Functional Requirements

| Attribute | Target | Rationale |
|-----------|--------|-----------|
| Application processing latency | < 5 seconds for initial validation, < 24 hours for credit decision | Regulatory (RBI FLDG) |
| Disbursement latency | < 2 hours from acceptance | Competitive, borrower experience |
| EMI collection accuracy | 100% — zero missed or double-debited EMIs | Financial integrity |
| System availability | 99.9% (< 9 hours/year) | Lending is not real-time like payments |
| Audit trail completeness | 100% — every state transition logged with actor, timestamp, reason | Regulatory requirement |
| Data retention | 10 years post-loan closure | RBI guidelines |
| Consistency | Strong for financial ledger, eventual for notifications | ACID for money |

---

## Assumptions

- Single-country operation (India / INR), but system designed to support multi-currency with schema extension
- Credit bureau integration (CIBIL / Experian) is external — modeled as interface
- Bank account debit (NACH mandate) handled via external payment processor (Razorpay, NPCI) — modeled as interface
- KYC assumed pre-completed for MVP (integration point defined, not implemented)
- No consumer-facing mobile app in scope — REST API consumed by web app or partner systems
- GST/tax computation on interest is out of scope (handled by accounting system downstream)

---

## Constraints

- Java + Spring Boot backend
- PostgreSQL for loan data (relational, ACID)
- Kafka for asynchronous workflows (disbursement saga, EMI collection pipeline)
- Redis for caching (loan summary, rate cards)
- No third-party BPM engine (Camunda/Temporal) in MVP — state machine managed in code

---

## Loan Types in Scope

| Loan Type | Disbursement | Repayment | Complexity |
|-----------|-------------|-----------|-----------|
| Personal Loan | Single lump sum | Fixed EMI | Low |
| Home Loan | Single lump sum | Fixed/floating EMI | Medium |
| BNPL (Buy Now Pay Later) | Merchant credit | 3-12 EMIs | Medium |
| Construction Loan | Tranched disbursement | Interest-only until completion | High |

MVP: Personal Loan only. V1: Home Loan + BNPL. V2: Construction Loan.

---

## Scale Estimation

### Application Volume

```
Daily loan applications:    50,000
Peak (festive season):      200,000/day
Applications per second:    50,000 / 86,400 ≈ 0.58 RPS average
Peak RPS:                   200,000 / 43,200 ≈ 4.6 RPS (12-hour peak window)
```

### Active Loan Portfolio

```
Active loans:               2,000,000
Avg loan tenure:            24 months
EMIs processed per day:     2,000,000 / 24 × (1/30) ≈ 2,778 EMIs/day
Monthly EMI batch:          ~66,667 EMIs on 1st of month
Peak EMI processing:        66,667 in 4-hour window = 4.6 EMIs/sec
```

### Storage Estimation

```
Loan application:           ~50 KB (with documents)
Active loan account:        ~10 KB (with amortization schedule)
EMI transaction record:     ~500 bytes
Storage/year:
  Applications: 50,000/day × 365 × 50 KB = 913 GB
  Loan accounts: 2M × 10 KB = 20 GB
  EMI records: 2M loans × 24 EMIs × 500 bytes = 24 GB
  Total: ~1 TB/year (before document storage)
```

### Document Storage

```
Avg documents per application: 5 documents × 2 MB = 10 MB
Daily: 50,000 × 10 MB = 500 GB/day (peak: 2 TB/day)
→ S3 / object storage mandatory for documents
```

---

## Read/Write Patterns

| Operation | Type | Frequency | Latency Target |
|-----------|------|-----------|----------------|
| Application submission | Write | Medium | < 2s |
| Underwriter dashboard | Read | High | < 500ms |
| Loan status query | Read | Very High | < 200ms |
| EMI schedule view | Read | High | < 200ms |
| EMI collection (batch) | Write | High (burst monthly) | Batch, not real-time |
| Repayment recording | Write | Medium | < 1s |
| Loan officer review | Read + Write | Medium | < 1s |

---

## Latency Expectations

| Workflow | Target |
|----------|--------|
| Application form submission | < 2 seconds |
| Document validation | < 5 seconds |
| Auto credit decision (rules engine) | < 30 seconds |
| Human underwriter decision | < 24 hours (SLA) |
| Disbursement after acceptance | < 2 hours |
| EMI debit confirmation | Same-day |
| Prepayment processing | < 1 hour |
