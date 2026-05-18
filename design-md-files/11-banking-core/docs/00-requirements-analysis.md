# 00 — Requirements Analysis: Banking Core System

## Objective

Define the functional and non-functional requirements, scale estimates, and constraints for a production-grade Banking Core System (CBS) that can handle retail banking operations — account management, transactions, payment rail integrations, compliance, and regulatory reporting. This document establishes the foundation upon which all subsequent design decisions are grounded.

---

## Problem Statement

Build a Core Banking System (CBS) that powers:
- Multi-product retail bank accounts (savings, current, fixed deposit, loan)
- Real-money ledger operations with double-entry bookkeeping
- Payment rail integrations (NEFT, RTGS, IMPS, SWIFT)
- Regulatory compliance (KYC, AML, RBI/SEBI mandates)
- Maker-checker approval workflows for sensitive operations
- End-of-day batch processing (interest calculation, reconciliation)
- 24x7 availability with zero-tolerance for data inconsistency

---

## 1. Functional Requirements

### 1.1 Customer & Account Management
- Onboard customers with full KYC verification (identity, address, income proof)
- Support multiple account types per customer: Savings, Current, Fixed Deposit (FD), Recurring Deposit (RD), Loan
- Account lifecycle management: open, activate, freeze, dormancy, close
- Nominee management, joint account holders, mandate management
- CIF (Customer Information File) — single view of customer across all products

### 1.2 Ledger & Transaction Processing
- Double-entry bookkeeping: every debit has a corresponding credit
- Immutable transaction journal (no updates, no deletes — only compensating entries)
- Intraday transaction processing with same-day value dating
- Posting date vs value date differentiation
- Fund hold / lien marking on accounts
- Overdraft limits and enforcement

### 1.3 Payment Rail Integration
- **NEFT**: National Electronic Funds Transfer (batch settlement, RBI-managed)
- **RTGS**: Real-Time Gross Settlement (for high-value transactions above ₹2L)
- **IMPS**: Immediate Payment Service (24x7 real-time)
- **SWIFT**: International wire transfers
- **UPI**: Unified Payments Interface integration via NPCI
- Inbound and outbound payment processing
- Payment status lifecycle tracking (initiated → processing → settled/failed/returned)

### 1.4 Maker-Checker (4-Eyes Principle)
- All sensitive operations require a second authorized officer to approve
- Role-based authorization levels (Teller → Branch Manager → Senior Manager)
- Approval matrix configurable per transaction type and amount band
- Time-boxed pending approvals with escalation
- Audit trail of who initiated, who approved, when, from what IP

### 1.5 Interest Calculation
- Daily interest accrual for savings, FD, RD, and loan accounts
- Batch job: end-of-day (EOD) interest run
- Interest posting on maturity / monthly / quarterly as per product configuration
- Penal interest for loan defaults
- NPA (Non-Performing Asset) classification and provisioning triggers

### 1.6 Reconciliation
- Intraday reconciliation: payment rails vs ledger
- EOD reconciliation: all accounts balance to general ledger (GL)
- Nostro/Vostro account reconciliation for SWIFT
- Exception management dashboard for breaks
- Auto-matching and manual resolution workflows

### 1.7 Statement Generation
- On-demand account statement (PDF/CSV)
- Scheduled monthly statement delivery (email, portal)
- Passbook entries (for branch use)
- Consolidated customer statement across all products

### 1.8 Compliance & Regulatory
- **KYC**: PAN, Aadhaar, video KYC, periodic re-KYC
- **AML**: Rule-based and ML-based suspicious transaction detection
- **CTR (Cash Transaction Reports)**: Auto-generate for cash transactions > ₹10L
- **STR (Suspicious Transaction Reports)**: File with FIU-IND
- **26AS reconciliation**: TDS deducted at source on FD interest
- **Basel III reporting**: Capital adequacy, liquidity ratios

---

## 2. Non-Functional Requirements

| Category | Requirement |
|---|---|
| Availability | 99.99% uptime (52 minutes downtime/year) |
| Latency | Account balance read < 50ms p99; Payment initiation < 200ms p99 |
| Throughput | 10,000 TPS peak (IMPS bursts on salary dates) |
| Data Durability | Zero data loss — RPO = 0 seconds |
| Recovery Time | RTO < 15 minutes for DR failover |
| Consistency | Strong consistency for ledger operations — CP over AP |
| Auditability | Every operation must be attributable, timestamped, immutable |
| Regulatory | RBI, SEBI, FATF, SWIFT compliance |
| Data Retention | 7 years for transactions, 10 years for audit logs |

---

## 3. Assumptions

- Initial deployment targets a mid-size retail bank (~5M customers, 8M accounts)
- Bank operates in India (RBI regulatory framework), with future SWIFT capability for international
- Users are a mix of retail customers (mobile/internet banking), branch staff (internal tools), and back-office operators
- Payment rails (NEFT/RTGS/IMPS) connect via a Payment Hub middleware layer or direct NPCI integration
- External KYC bureaus (CKYC Registry, UIDAI Aadhaar) are accessible via APIs
- HSM (Hardware Security Module) is available in the data center for key management
- Two data centers (Primary + DR) are available in different geographic zones

---

## 4. Constraints

- **Regulatory**: All financial data must reside in India (RBI data localization mandate)
- **Operational**: Core ledger cannot have scheduled downtime — maintenance via rolling updates
- **Audit**: Every state change must be traceable to a human actor or automated job
- **Irreversibility**: Financial postings are immutable — corrections only via compensating entries
- **Approval workflows**: Cannot bypass maker-checker via API, only via physical override with dual authorization at the DB level (emergency break-glass)

---

## 5. Scale Estimation (Back-of-the-Envelope)

### Customer and Account Scale

```
Customers:       5,000,000
Accounts:        8,000,000 (average 1.6 accounts per customer)
Active daily:    2,000,000 (40% DAU)
Transactions/day (steady): 15,000,000
Transactions/day (peak — salary day): 45,000,000
```

### Transaction RPS

```
Average TPS (daily avg):  15M / 86400 = ~174 TPS
Peak TPS (salary burst):  45M / 86400 = ~520 TPS sustained
Intraday burst peak:      ~3,000 TPS (IMPS morning rush)
NEFT batch window:        1M transactions in 30 min → ~556 TPS burst
```

### Storage Estimation

```
Transaction record:     ~500 bytes
Journal entry (2 legs): ~1 KB
Daily new transactions: 15M × 1 KB = 15 GB/day
Annual transactions:    15 GB × 365 = ~5.5 TB/year
7-year retention:       ~38 TB for transactions alone

Customer CIF:           ~10 KB per customer
5M customers:           ~50 GB
Account metadata:       ~2 KB per account
8M accounts:            ~16 GB

Audit log:              ~300 bytes per event
100M events/day:        ~30 GB/day
10-year audit:          ~110 TB
```

### Bandwidth

```
Peak inbound (IMPS):    3,000 TPS × 2 KB payload = ~6 MB/s inbound
Statement generation:   1M PDFs/month = ~33K/day = burst ~5 GB/day during EOD
```

---

## 6. Read/Write Patterns

| Operation | Pattern | Frequency | Latency Requirement |
|---|---|---|---|
| Balance inquiry | Read-heavy | Very High | < 30ms |
| Transaction posting | Write-heavy, consistent | High | < 100ms |
| Statement generation | Read-heavy, batch | Moderate | < 5 seconds |
| AML screening | Read-heavy, near-real-time | High | < 500ms |
| Interest accrual | Batch write | Daily (EOD) | Minutes to hours |
| Reconciliation | Read + write | Daily (EOD) | Hours |
| Audit log writes | Append-only write | Very High | Async acceptable |

---

## 7. Traffic Patterns

- **Morning peak** (9 AM – 11 AM): Branch opens, NEFT windows, RTGS opens → ~3x average load
- **Salary day** (28th–1st of month): IMPS volume spikes → ~8-10x average load
- **EOD batch** (6 PM – 8 PM): Interest runs, reconciliation, report generation → DB write-heavy
- **Festival seasons**: High cash withdrawals, UPI spikes
- **NEFT batch windows**: Every 30 minutes, NEFT batches are sent → predictable burst every half hour

---

## 8. Latency Expectations

| API | p50 | p95 | p99 |
|---|---|---|---|
| Balance read | 10ms | 25ms | 50ms |
| Transaction initiation | 50ms | 120ms | 200ms |
| IMPS payment | 100ms | 300ms | 500ms |
| NEFT submission | 200ms | 500ms | 1s |
| Statement (on-demand) | 500ms | 2s | 5s |
| AML screening | 50ms | 200ms | 500ms |

---

## 9. Availability Targets

```
Core Ledger:        99.99% (< 52 min/year downtime)
Payment Rails:      99.95% (< 4.4 hours/year) — rail availability depends on NPCI/RBI
Internet Banking:   99.9% (< 8.7 hours/year)
Statement Gen:      99.5% (non-critical path)
Batch Jobs:         99.9% — must complete within SLA windows
```

---

## 10. Interview-Level Discussion Points

**Q: Why CP over AP for a banking system?**
A: In a banking context, showing stale or incorrect balances leads to overdraft fraud, regulatory fines, and loss of customer trust. A wrong balance read that allows a double-spend or overdrawn state is catastrophically worse than a momentary unavailability. The system must choose consistency. CAP theorem forces this choice — we accept brief unavailability over data inconsistency.

**Q: How do you handle the CAP tradeoff during a network partition?**
A: Core ledger nodes will refuse writes if they cannot confirm quorum. Read traffic can be served from replicas with a "balance as of X timestamp" indicator. Payment channels may queue transactions locally and drain when partition heals — with idempotency keys ensuring no duplicate processing.

**Q: What's the difference between posting date and value date?**
A: Posting date is when the transaction is recorded in the system. Value date is when funds are actually available (interest calculations begin). For NEFT, a transaction may post on Day 1 but have a value date of Day 2. This distinction is critical for interest calculation correctness.

**Q: What is a CTR and when must you file it?**
A: Cash Transaction Report — mandatory RBI filing for any cash deposit or withdrawal exceeding ₹10 lakhs in a single day across all accounts. The system must aggregate same-day cash transactions per customer and auto-trigger CTR generation. Manual review is required before submission.

**Q: Why not event sourcing for the entire system?**
A: Event sourcing is appropriate for the audit log and ledger journal, but not as the sole persistence model for the entire system. Reconstructing account state from events for every read operation is impractical at banking scale. Hybrid: current state in relational DB + immutable event log for audit and replay.
