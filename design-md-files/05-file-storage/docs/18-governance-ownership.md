# 18 — Governance & Ownership: File Storage System

---

## Objective

Define service ownership, on-call accountability, and change governance for a storage platform where durability and data integrity are paramount. Ownership follows the extraction order in `01-high-level-architecture.md` (Upload first, then Search, Sync, Preview/Notification).

---

## Service Ownership

| Service | Owning team | Primary SLO / responsibility |
|---|---|---|
| Upload Service | Upload team | Resumable upload; ack < 500ms; manifest integrity |
| Storage Service | Storage/Platform team | Object-storage abstraction; **dedup correctness** |
| Metadata Service | Metadata team | Strong consistency; browse latency |
| Sync Service | Sync team | Change-feed correctness; lag < 5s |
| Search Service | Data team | Index freshness; search < 500ms |
| Preview Service | Platform team | Thumbnail/preview generation |
| Notification Service | Platform team | Share/collaboration events |
| Quota Service | Billing/Platform team | Storage accounting integrity |
| Object storage (S3) | Storage/SRE | Durability (11 nines), lifecycle |
| PostgreSQL / Redis / Kafka / ES | Platform/SRE | Shared-infra availability |

---

## On-Call & Escalation

| Tier | Responsibility | Response target |
|---|---|---|
| L1 primary | Ack, runbook, mitigate | Ack < 10 min |
| L2 service owner | Code-level diagnosis | < 20 min |
| L3 eng lead + SRE | Data-integrity / capacity | As needed |

- Any suspected **data-loss or dedup-corruption** event is a P1 regardless of user-facing latency — integrity outranks availability here.
- Presigned-URL abuse alerts route to Security.
- Consumer-lag (search) and quota-drift have dedicated alerts/owners.

---

## SLO & Error-Budget Governance

- Durability (11 nines) and metadata consistency are owned by Storage/SRE and Metadata respectively.
- RPO < 1 min (metadata) / RTO < 15 min owned by SRE and validated in DR drills.
- Monthly error-budget review; exhaustion freezes feature work for the owning service.

---

## Change Management

| Change type | Governance |
|---|---|
| Metadata schema | Metadata team review; backward-compatible migration |
| Dedup / content-addressing logic | Storage team review (corruption risk → mandatory concurrency test) |
| Presigned-URL policy / TTL | Security review |
| Architecture decision | ADR in `/docs/adr/` |

---

## Documentation Governance

- `/docs` reviewed quarterly and on any architecture change.
- Runbooks for dedup-reconciliation, quota-drift, and DR restore kept current; doc update part of PR done-ness.

---

## Interview Discussion Points

- **Why does integrity outrank availability in escalation here?** A storage system that loses or corrupts data has failed its core promise; a slow read is recoverable, lost bytes are not.
- **Who owns the dedup logic?** Storage team — and any change requires the concurrency/corruption test because the failure is silent.
- **Who owns the quota-vs-upload timing gap?** Billing/Platform, with a reconciliation job; it is a named governance concern, not an unowned edge case.
