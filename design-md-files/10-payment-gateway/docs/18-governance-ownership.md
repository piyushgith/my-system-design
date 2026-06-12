# 18 — Governance & Ownership: Payment Gateway / Wallet System

---

## Objective

Define service ownership, on-call accountability, and change governance for a regulated financial system. Here governance is not overhead — PCI DSS and RBI Payment Aggregator guidelines require documented ownership, segregated duties, audited change control, and named accountability for the ledger and the cardholder-data environment.

---

## Service / Module Ownership

| Service / Module | Owning team | Primary SLO / responsibility |
|---|---|---|
| Payment Core | Payments team | Auth/capture/refund; idempotency; p99 < 500ms |
| Wallet Core | Wallet team | Balance integrity; transfer p99 < 200ms |
| Ledger Module | Ledger/Finance-eng team | **Double-entry invariant (debits = credits)** |
| Merchant Module | Merchant team | API keys, webhooks, dashboard |
| Settlement Service | Settlement team | Batch payout + reconciliation correctness |
| Notification Service | Platform team | Webhook/email/SMS dispatch |
| **Vault Service** | Security team | PCI CDE; tokenization; PAN never leaves vault |
| Fraud Engine | Risk team | Inline rules < 50ms; ML scoring |
| PostgreSQL / Redis / Kafka | Platform/SRE | Shared-infra availability |
| Acquirer / card-network / NPCI relationships | Payments team | Vendor contracts, certification |

**Segregation of duties:** the Vault (CDE) is owned by Security and is separately auditable; no single team has end-to-end access to raw card data and ledger writes.

---

## On-Call & Escalation

| Tier | Responsibility | Response target |
|---|---|---|
| L1 primary | Ack, runbook, mitigate | Ack < 5 min (payment/wallet, 99.99%) |
| L2 service owner | Code-level diagnosis | < 15 min |
| L3 eng lead + Security/Compliance | Regulatory / financial incidents | Immediate for money-impacting events |

- **Any ledger imbalance pages immediately** — it is the highest-severity signal in the system.
- Double-charge, debit-without-credit, and settlement-mismatch follow an audited incident process with Finance + Compliance.
- A CDE/Vault security event escalates to Security + Compliance under the breach-response plan.

---

## SLO & Error-Budget Governance

- Per-service SLOs from `00-requirements-analysis.md`.
- **Ledger correctness is a hard invariant, not a budgeted SLO** — there is no acceptable error rate for imbalance; it is a stop-the-line condition.
- RPO 0 for financial data, owned by Platform/SRE, validated in mandatory DR drills (RBI requirement).
- Monthly error-budget review for latency SLOs; correctness incidents are reviewed individually.

---

## Change Management

| Change type | Governance |
|---|---|
| Ledger logic | Ledger team + Finance review + ledger-invariant test green; **two-person approval** |
| Payment / refund flow | Payments + Security; idempotency test green |
| Vault / CDE change | Security review under PCI change control; audit-logged |
| Fraud rules | Risk team review; fail-safe default |
| Settlement / reconciliation | Settlement + Finance review |
| Architecture decision | ADR in `/docs/adr/`; compliance-impacting ADRs reviewed by Compliance |

All changes to financial-core code require two-person review and are captured in an immutable audit trail (7-year retention).

---

## Documentation Governance

- `/docs` reviewed quarterly and on any change to ledger, CDE, or settlement, by the owning team plus Compliance.
- Recovery runbooks (double-charge, debit-without-credit, settlement mismatch, primary failover) kept current and drilled; doc update is part of PR done-ness.

---

## Interview Discussion Points

- **Why is ledger imbalance "stop-the-line"?** Unlike latency, there is no acceptable rate of broken books — any imbalance is a correctness and compliance failure that pages immediately.
- **Why segregate Vault ownership?** PCI requires the CDE to be network-isolated and separately auditable; Security owning it enforces segregation of duties.
- **Why two-person approval on the financial core?** A single missed branch is direct money loss; segregated, dual review and an immutable audit trail are regulatory expectations, not gold-plating.
