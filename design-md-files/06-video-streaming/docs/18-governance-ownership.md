# 18 — Governance & Ownership: Video Streaming Platform

---

## Objective

Define service ownership, on-call accountability, and change governance for a large microservice platform. With 12+ services and a playback path that must never depend on peripheral services, ownership and graceful-degradation responsibilities must be explicit.

---

## Service Ownership

| Service | Owning team | Primary SLO / responsibility |
|---|---|---|
| Upload Service | Ingest team | Resumable upload; ack latency |
| Transcoding Orchestrator + Workers | Media team | Transcode SLA < 15 min |
| CDN Origin Service | Delivery team | Manifest correctness; origin protection |
| Metadata Service | Catalog team | Strong consistency; API p99 < 200ms |
| User Service | Identity team | Auth, JWT |
| Search Service | Discovery team | Search p99 < 500ms |
| Recommendation Service | Discovery/ML team | Feed quality (degradable) |
| Engagement Service | Engagement team | Likes/comments/subs |
| Analytics Service | Data team | View-count exactness |
| Notification Service | Platform team | Subscriber fan-out |
| Moderation Service | Trust & Safety | DMCA/content review |
| CDN / Edge | Delivery + SRE | > 95% hit; 25 Tbps egress |
| Kafka / S3 / PostgreSQL / Redis | Platform/SRE | Shared-infra availability |

---

## On-Call & Escalation

| Tier | Responsibility | Response target |
|---|---|---|
| L1 primary | Ack, runbook, mitigate | Ack < 5 min (playback path) |
| L2 service owner | Code-level diagnosis | < 15 min |
| L3 eng lead + SRE | Cross-cutting / capacity | As needed |

- **Playback path (delivery, origin, metadata)** pages immediately; recommendation/search degradation is ticket-first because the design mandates graceful degradation.
- DMCA takedown is a Trust & Safety + Legal governance path with an SLA.
- RTO < 5 min (stream delivery) owned by Delivery + SRE; validated in DR drills.

---

## SLO & Error-Budget Governance

- Each service owns its SLO from `00-requirements-analysis.md`.
- **Graceful-degradation ownership:** each peripheral service (rec, search, engagement) must document and own its degraded-mode behavior; the playback path must have no hard dependency on it. This is reviewed, not assumed.
- Monthly error-budget review; exhaustion freezes the owning service's feature work.

---

## Change Management

| Change type | Governance |
|---|---|
| Manifest / segment format | Delivery team review (CDN-cache impact) |
| Transcode rendition ladder | Media team review (storage + cost impact) |
| Event schema (View, Transcode) | Schema Registry compatibility check |
| DMCA / moderation policy | Trust & Safety + Legal sign-off |
| Architecture decision | ADR in `/docs/adr/` |

---

## Documentation Governance

- `/docs` reviewed quarterly and on any topology change.
- Runbooks for origin-shield failover, transcode-DLQ recovery, and DMCA purge kept current; doc update part of PR done-ness.

---

## Interview Discussion Points

- **Who is accountable for graceful degradation?** Each peripheral-service owner documents and tests its degraded mode; the playback path team verifies no hard dependency exists.
- **Who owns the 25 Tbps egress relationship?** Delivery + SRE own the CDN contract and hit-ratio SLO — it is a vendor-management responsibility, not just config.
- **How do you prevent a rec-service outage from hurting playback?** Ownership review enforces that the playback path has no synchronous dependency on rec/search.
