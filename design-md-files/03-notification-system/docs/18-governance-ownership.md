# 18 — Governance & Ownership: Notification System

---

## Objective

Define ownership, on-call accountability, and change governance across the system's many services. With per-channel dispatchers and external providers, clear ownership is essential — a SendGrid outage and an SMS DND breach have different owners and escalation paths.

---

## Service Ownership

| Service | Owning team | Primary SLO / responsibility |
|---|---|---|
| Notification API | Platform team | Ingestion 99.9%; idempotency enforcement |
| Fanout Service | Platform team | Correct routing + quiet-hours; campaign throughput |
| Email Dispatcher | Channels team | Email delivery; SendGrid/SES failover |
| SMS Dispatcher | Channels team | SMS delivery; **DND/regulatory compliance** |
| Push Dispatcher | Channels team | FCM/APNs delivery; device-token handling |
| In-App Dispatcher | Channels team | Inbox write availability |
| Template Service | Content/Platform team | Template correctness + versioning |
| Preference Service | Platform team | Preference accuracy; opt-out honored |
| Scheduler Service | Platform team | Scheduled delivery accuracy |
| Delivery Log Service | Data/Analytics team | Audit completeness (append-only) |
| Infra (Kafka, PostgreSQL, Redis, ClickHouse) | Platform/SRE | Shared-infra availability |
| Provider relationships (SendGrid/Twilio/FCM) | Channels team | Contract, rate limits, failover plan |

---

## On-Call & Escalation

| Tier | Responsibility | Response target |
|---|---|---|
| L1 primary | Ack, runbook, mitigate | Ack < 5 min for transactional (OTP) path |
| L2 service owner | Code/consumer-level fix | < 15 min |
| L3 eng lead + Compliance | Regulatory/provider escalation | As needed |

- **Transactional (OTP) path** pages immediately (99.95% target); marketing/campaign issues are ticket-first.
- **DLQ depth and consumer lag** have dedicated alerts with owners.
- Compliance (CAN-SPAM/GDPR/DND) breaches escalate to a named Compliance owner — an engineering fix alone is insufficient.

---

## SLO & Error-Budget Governance

- Per-channel SLOs owned by the Channels team; ingestion + fanout by Platform.
- Monthly error-budget review per channel; a channel's exhausted budget freezes that channel's feature work, not the whole system (the isolation principle applied to governance).

---

## Change Management

| Change type | Governance |
|---|---|
| Message schema (Avro) | Schema Registry compatibility check; backward-compatible required |
| Template change | Content owner review; staged rollout (bad template is a documented failure mode) |
| New channel/provider | Architecture ADR + provider failover plan |
| Compliance rule (DND, quiet hours) | Compliance sign-off mandatory |

---

## Documentation Governance

- `/docs` reviewed quarterly and on any topology/provider change by the owning team.
- Runbooks for each consumer group and provider-failover path kept current; doc update is part of PR done-ness.

---

## Interview Discussion Points

- **Why split ownership by channel?** Each channel has distinct failure modes, compliance rules, and providers — one owner per channel keeps accountability sharp.
- **Who owns a regulatory breach?** Compliance, with engineering support — it is escalated as a governance incident, not just a bug.
- **How does error-budget isolation mirror the architecture?** Per-channel budgets mean a struggling email channel doesn't freeze SMS work — the same isolation that per-channel topics give operationally.
