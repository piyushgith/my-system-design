# 18 — Governance & Ownership: Food Delivery Platform

---

## Objective

Define service ownership, on-call accountability, and change governance for a 3-sided marketplace coordinated by an order Saga. Because an order spans multiple domain owners, the governance of the saga and its compensation paths is as important as the code.

---

## Service Ownership

| Service | Owning team | Primary SLO / responsibility |
|---|---|---|
| Order Service (Saga orchestrator) | Order team | Order p99 < 2s; **saga + compensation correctness** |
| Payment Service | Payments team | Charge/refund correctness; PCI scope |
| Restaurant Service | Merchant team | Onboarding, availability, order notify |
| Menu Service | Catalog team | Menu CRUD; search index source |
| Delivery Service | Delivery team | Partner matching; tracking; 50K loc RPS |
| Search Service | Discovery team | Search p99 < 500ms |
| Coupon Service | Growth team | Coupon validity; fraud/single-redemption |
| Notification Service | Platform team | Customer/partner/restaurant notifications |
| Analytics Service | Data team | Business metrics |
| User Service | Identity team | Profile, addresses, sessions |
| Kafka / PostgreSQL / Redis / ES | Platform/SRE | Shared-infra availability |

---

## On-Call & Escalation

| Tier | Responsibility | Response target |
|---|---|---|
| L1 primary | Ack, runbook, mitigate | Ack < 5 min (order placement, 99.99%) |
| L2 service owner | Code-level diagnosis | < 15 min |
| L3 eng lead + SRE | Cross-cutting / saga incidents | As needed |

- **Stuck-saga and DLQ-depth alerts** have explicit owners — a saga stuck in a non-terminal state is a money/stock risk, treated as P1.
- Payments incidents follow the PCI-scoped escalation.
- Peak-window (lunch/dinner) capacity is a scheduled, owned operational concern.

---

## SLO & Error-Budget Governance

- Per-service SLOs from `00-requirements-analysis.md`.
- **Saga ownership:** the Order team owns the saga state machine; every compensation branch must have a test (see `17-testing-strategy.md`) and a runbook. No compensation path ships unowned.
- Monthly error-budget review; exhaustion freezes the owning service's feature work.

---

## Change Management

| Change type | Governance |
|---|---|
| Saga state machine / compensation | Order team review + full compensation-test matrix green |
| Event schema | Schema Registry compatibility check |
| Payment flow | Payments + Security (PCI) |
| Coupon / promo rules | Growth + Finance review (fraud + margin) |
| Architecture decision | ADR in `/docs/adr/` |

---

## Documentation Governance

- `/docs` reviewed quarterly and on any saga/topology change.
- Runbooks for each saga compensation branch, outbox-poller, and DLQ recovery kept current; doc update part of PR done-ness.

---

## Interview Discussion Points

- **Who owns the order saga?** The Order team owns it end-to-end, including every compensation branch — distributed ownership of a saga is how rollbacks get missed.
- **Why are stuck sagas P1?** A non-terminal order can mean money taken without delivery or stock held without sale — financial correctness, not just UX.
- **How is the compensation matrix governed?** No compensation path ships without a test and a runbook; CI enforces branch coverage.
