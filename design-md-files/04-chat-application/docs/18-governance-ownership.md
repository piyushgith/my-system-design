# 18 — Governance & Ownership: Chat Application

---

## Objective

Define service ownership, on-call accountability, and change governance for a system with sharply different scaling tiers (stateful connection servers vs stateless messaging vs purpose-built stores). Ownership follows those tiers so the hardest-to-operate components have dedicated, expert owners.

---

## Service Ownership

| Service / Component | Owning team | Primary SLO / responsibility |
|---|---|---|
| WebSocket Gateway (connection tier) | Connection/Infra team | Connection stability; 50K conn/server; reconnect |
| Message Service | Core Messaging team | Durability (zero acknowledged loss), ordering |
| Fan-Out Service | Core Messaging team | Delivery fan-out; group expansion |
| Presence Service | Platform team | Presence accuracy; TTL correctness |
| Conversation Service | Platform team | Metadata, membership, unread counts |
| Connection Registry (Redis) | Connection/Infra team | Routing correctness |
| Notification Service | Platform team | Offline push (delegated per design #3) |
| Media Service | Platform team | Upload/CDN; virus scan |
| Search Service | Data team | Message search freshness |
| Cassandra | Data Platform team | Message-store availability + ops |
| Kafka | Platform/SRE | Durable backbone |
| Redis (Pub/Sub + presence) | Connection/Infra team | Sub-ms delivery bus |

Critical-hire roles (Cassandra expert, Kafka expert, WebSocket specialist, SRE) map directly to these ownership lines — see `15-implementation-roadmap.md` Team Scaling.

---

## On-Call & Escalation

| Tier | Responsibility | Response target |
|---|---|---|
| L1 primary | Ack, runbook, mitigate | Ack < 5 min (delivery path, 99.99%) |
| L2 service owner | Code-level diagnosis | < 15 min |
| L3 eng lead + SRE | Cross-cutting / capacity | As needed |

- Message-delivery alerts page immediately; presence/search lag is ticket-first.
- Connection-tier mass-disconnect (server loss) is a P1 — reconnect storms can cascade.
- Blameless reviews; chaos game days from Phase 2.

---

## SLO & Error-Budget Governance

- Delivery SLO (p99 < 500ms, 99.99%) is owned by Core Messaging and is the tightest constraint.
- Reliability Engineering team owns SLO dashboards, error budgets, and chaos testing from Phase 3.
- Budget exhaustion freezes feature work for the owning service.

---

## Change Management

| Change type | Governance |
|---|---|
| Cassandra schema / partition change | Data Platform review (partition design is hard to undo) |
| Protobuf / WebSocket frame change | Backward-compat check; contract test |
| Kafka topic / partition change | Platform/SRE review (ordering depends on partitioning) |
| Architecture decision | ADR in `/docs/adr/` |

---

## Documentation Governance

- `/docs` reviewed quarterly and on any topology change.
- Runbooks for connection-tier failover, Cassandra ops, and Kafka rebalancing kept current; doc update part of PR done-ness.

---

## Interview Discussion Points

- **Why does the connection tier get its own owner?** Stateful WebSocket servers are the hardest component to scale and operate; they need a dedicated, expert team.
- **Who owns ordering guarantees?** Core Messaging — and any Kafka partitioning change goes through them because ordering depends on partition keys.
- **How does ownership map to hiring?** Each critical-hire role corresponds to an ownership line; the org chart mirrors the architecture (Conway's Law, used deliberately).
