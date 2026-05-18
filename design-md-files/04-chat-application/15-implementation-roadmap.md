# 15 — Implementation Roadmap: Chat Application

---

## Objective

Define a phased implementation plan that progressively builds the chat platform from a functional MVP to Taking-scale production system. Each phase has clear scope, technical evolution, infrastructure evolution, team requirements, and risk assessment.

---

## Phase Overview

| Phase | Name | Duration | Scale Target | Team Size |
|-------|------|----------|-------------|-----------|
| Phase 0 | MVP | 6–8 weeks | 1K concurrent users | 2–3 engineers |
| Phase 1 | Production Foundation | 8–12 weeks | 100K concurrent users | 4–6 engineers |
| Phase 2 | Scale & Reliability | 12–16 weeks | 1M concurrent users | 8–12 engineers |
| Phase 3 | Platform Features | 12–16 weeks | 10M concurrent users | 15–20 engineers |
| Phase 4 | Taking Scale | Ongoing | 50M+ concurrent users | 30+ engineers |

---

## Phase 0: MVP — "Get It Working"

### Scope

**What we build:**
- 1:1 text messaging via WebSockets
- Basic group messaging (up to 20 members)
- User authentication (JWT)
- Message persistence (history)
- Basic presence (online/offline)
- Simple push notifications for offline users

**What we skip:**
- Media attachments
- Read receipts
- Typing indicators
- Multi-device sync
- Search
- Message reactions/threads

### Architecture

Single Spring Boot application (modular monolith):
```
chat-monolith/
├── module-ws/        (WebSocket handler)
├── module-messaging/ (send, persist, retrieve)
├── module-presence/  (Redis TTL-based presence)
├── module-conversation/ (groups, membership)
└── module-notification/ (FCM push)
```

### Data Stack

| Component | MVP Choice | Notes |
|-----------|-----------|-------|
| Messages | PostgreSQL | Simple start; will migrate later |
| Presence | Redis | Correct from day 1 (no migration needed) |
| Queue | In-process (Spring Events) | No Kafka yet |
| WebSocket | Spring WebSocket + STOMP | Simple pub/sub within single instance |
| Notifications | Firebase Admin SDK direct | No queue; direct call |

### Limitations of Phase 0

- Single JVM instance → no horizontal scaling of WebSocket connections
- PostgreSQL message table will become a bottleneck at ~10M messages
- No fan-out for groups → direct queries per member on message send
- Single-server WebSocket (all clients must connect to same server)

### MVP Milestones

| Week | Milestone |
|------|----------|
| 1–2 | WebSocket connection, JWT auth, basic message send/receive |
| 3–4 | Group creation, message history API, PostgreSQL schema |
| 5–6 | Presence (Redis TTL), basic push notifications |
| 7–8 | Testing, bug fixes, monitoring basics |

---

## Phase 1: Production Foundation — "Make It Reliable"

### Scope

**New in Phase 1:**
- Horizontal WebSocket scaling (Connection Registry + Redis Pub/Sub)
- Kafka introduction for async fan-out
- Message delivery receipts (SENT / DELIVERED / READ)
- Typing indicators
- Media attachments (S3 + pre-signed URLs)
- Multi-device support
- Rate limiting
- Proper observability (Prometheus + Grafana)

**Architecture Evolution:**

Modular monolith with external state (Redis + Kafka) is now horizontally scalable:
```
Multiple instances of chat-monolith all share:
  Redis (Connection Registry + Presence)
  Kafka (async fan-out)
  PostgreSQL (with read replica for history reads)
```

### Why This Phasing?

Adding Kafka to an MVP would add operational complexity before you even know if the product works. Phase 0 proves the product; Phase 1 makes it horizontally scalable. The modular monolith structure makes extraction to microservices straightforward in Phase 2.

### Data Stack Changes

| Component | Phase 0 | Phase 1 | Why |
|-----------|---------|---------|-----|
| Fan-out | In-process Spring Events | Kafka consumer group | Horizontal scale of fan-out |
| WebSocket routing | Single server (no routing needed) | Connection Registry (Redis) | Multiple WS servers |
| Message delivery | Synchronous | Async (Kafka) | Decouple send from deliver |
| Storage | PostgreSQL | PostgreSQL + S3 | Media files |

### Phase 1 Milestones

| Week | Milestone |
|------|----------|
| 1–3 | Connection Registry, Redis Pub/Sub, horizontal WS scaling |
| 4–6 | Kafka fan-out, delivery receipts (DELIVERED/READ) |
| 7–8 | Typing indicators, media upload (S3 pre-sign) |
| 9–10 | Multi-device sync (device registry, sync on reconnect) |
| 11–12 | Rate limiting, observability, load testing to 100K |

---

## Phase 2: Scale & Reliability — "Make It Fast at Scale"

### Scope

**New in Phase 2:**
- **Cassandra migration**: Migrate messages from PostgreSQL to Cassandra
- **Service extraction**: Split Fan-Out, Presence, Conversation into separate services
- **Full Outbox pattern**: Guaranteed Kafka delivery
- **Elasticsearch**: Message search
- **Circuit breakers**: Resilience4j across all service calls
- **Multi-region**: Active-passive (US primary + EU standby)
- **Comprehensive chaos testing**

### The PostgreSQL → Cassandra Migration

This is the highest-risk operation in the roadmap. Strategy:

```
Step 1: Add Cassandra write alongside PostgreSQL (dual-write)
  - All new messages write to BOTH PostgreSQL AND Cassandra
  - Read path: still PostgreSQL

Step 2: Backfill historical messages
  - Batch job reads PostgreSQL messages, writes to Cassandra
  - Run during low-traffic hours
  - Verify: row count + hash comparison

Step 3: Switch read path to Cassandra
  - Deploy new history endpoint reading from Cassandra
  - Feature flag to control rollout (start with 1% of users)
  - Monitor: latency, error rate

Step 4: Stop writing to PostgreSQL (messages table)
  - Keep conversation metadata, members in PostgreSQL
  - Drop PostgreSQL messages table after 30-day observation

Step 5: Archive PostgreSQL messages backup to S3 (for recovery)
```

**Risk**: If Cassandra reads are slower than PostgreSQL for small datasets (they can be at low scale), temporarily increase Cassandra local cache settings.

### Service Extraction Strategy

Extract services when a specific module becomes a scaling bottleneck:

| Service | Extract When |
|---------|-------------|
| Fan-Out | CPU is the bottleneck in the monolith under fan-out load |
| Presence | Heartbeat volume saturates the monolith's Redis connections |
| Conversation | Membership query volume impacts message service latency |
| Media | Bandwidth-heavy operations slow down message processing |

Each extraction:
1. Create new independent service from existing module
2. Define gRPC interface between new service and monolith
3. Route traffic to new service (feature flag)
4. Remove module from monolith

### Phase 2 Milestones

| Week | Milestone |
|------|----------|
| 1–4 | Cassandra dual-write + backfill |
| 5–7 | Cassandra read path switchover |
| 8–10 | Service extraction (Fan-Out, Presence) |
| 11–12 | Elasticsearch search integration |
| 13–14 | Multi-region (Cassandra replication + Kafka MirrorMaker) |
| 15–16 | Chaos testing, SLO validation at 1M users |

---

## Phase 3: Platform Features — "Make It Complete"

### Scope

**New in Phase 3:**
- Message reactions (emoji)
- Message threading (reply-to + thread view)
- Message edit (with edit history)
- Mention notifications (@user, @here, @channel)
- Link preview unfurling
- Message pinning
- Conversation archiving
- Advanced search (date filters, sender filters)
- Analytics dashboard for users (message volume, etc.)
- Bot API / Webhook integrations (Slack App Platform equivalent)

### Technical Challenges in Phase 3

**Message Reactions**: A reaction is associated with a message. Options:
- Store as a separate table in PostgreSQL: `message_reactions(message_id, user_id, emoji)` — simple, but creates hot rows for popular messages
- Store as a Cassandra row: `reactions:{message_id}` → set of `{emoji: count}` — denormalized but fast read

**Threading**: Thread is a conversation within a conversation. Options:
- Embed thread messages in the parent conversation as reply_to chain
- Create a new "thread" conversation linked to the parent message (Slack's approach)

**Link Preview**: Unfurling is a background job:
1. Message arrives with URL → Message Service detects URL pattern
2. Async job (Kafka event) → unfurl-service fetches URL metadata (OpenGraph tags)
3. Unfurl service caches preview in Redis
4. Pushes `MESSAGE_ENRICHED` event to clients → clients update UI with link preview

**Webhook Integrations**: 
- Allow third-party services to send messages to conversations via webhook URLs
- Auth: per-webhook tokens, not user JWTs
- Rate limiting: 30 messages/minute per webhook

---

## Phase 4: Taking Scale — "Make It Global"

### Scope

**New in Phase 4:**
- **End-to-end encryption** (Signal protocol)
- **Voice and video calls** (WebRTC + TURN/STUN servers)
- **Active-active multi-region** (not just active-passive)
- **Extreme fan-out optimization** (lazy fan-out for large channels)
- **Real-time translation** (ML-based)
- **Content moderation** (automated + human review pipeline)
- **Compliance features** (data retention policies, DLP, eDiscovery)

### E2EE Architecture (Phase 4)

Requires:
1. Key Distribution Service: stores users' public keys (identity key, signed prekeys, one-time prekeys)
2. Client-side encryption library (Signal Protocol port to Java/Swift/Kotlin/TypeScript)
3. Backup encryption service: encrypted backups with user-controlled keys
4. Server becomes "dumb pipe": stores + routes ciphertext only

**Operational impact**:
- Search disabled for E2EE conversations
- Moderation must shift to client-side scanning
- Support teams can no longer read message content

### Voice and Video Calls (Phase 4)

Separate subsystem entirely:
- **Signaling**: WebSocket messages to exchange SDP offers/answers between peers
- **Media**: WebRTC peer-to-peer when possible; TURN servers for NAT traversal
- **Recording**: Optional cloud recording via media server (Janus/Kurento)
- **Call routing**: Not through Chat servers — dedicated Call Service

### Active-Active Multi-Region

Challenge: A message sent in US-East is immediately acknowledged to the sender. If Bob in EU-West is the recipient, the message must be delivered from EU-West's Cassandra (after replication). But replication has ~100ms lag.

Solution: **Regional home-region assignment**
- Each conversation is assigned a "home region" (where it was created)
- Writes always go to home region's Cassandra (synchronously)
- Other regions receive async replicas
- If the sender is in a different region than the conversation's home region: accept the latency of cross-region write

---

## Implementation Risk Register

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|-----------|
| PostgreSQL → Cassandra migration data loss | Medium | Critical | Dual-write + verification + 30-day parallel run |
| Fan-out service overload on large groups | High | High | Lazy fan-out + conversation-level channels |
| Redis Connection Registry becoming a hot spot | Medium | High | Redis Cluster sharding + server-level pub/sub channels |
| WebSocket connection drain during rolling update | Medium | High | `maxUnavailable: 0` + preStop hook + 60-second drain |
| Cassandra partition hotspot on popular conversations | Low | Medium | time_bucket partitioning contains partition size |
| Kafka consumer lag during Fan-Out spikes | High | Medium | HPA on consumer lag metric + priority topics |
| E2EE key distribution attack (MitM) | Low | Critical | Safety numbers / key fingerprint verification |

---

## Team Scaling Considerations

### Phase 0–1: 2–6 engineers
- All engineers are full-stack; everyone touches every component
- No specialization yet; shared codebase in a monorepo

### Phase 2: 8–12 engineers
- **Infrastructure team** (2): manages Cassandra, Kafka, Kubernetes
- **Core messaging team** (4): message service, fan-out, Cassandra
- **Platform team** (2): presence, conversations, identity
- **Mobile/Web team** (2–4): client SDK, reconnect logic, offline sync

### Phase 3–4: 20–50+ engineers
- Dedicated teams per service (Fan-Out team, Presence team, Messaging team)
- **Reliability Engineering** team: SLO monitoring, chaos testing, capacity planning
- **Security team**: E2EE, audit, compliance
- **Data Platform team**: analytics pipeline, Cassandra operations, archival

### Critical Hires

| Role | Why Critical |
|------|------------|
| Cassandra DBA / Expert | Cassandra's operational complexity is extreme at scale |
| Kafka Expert | Consumer group tuning, topic management, MirrorMaker 2 |
| WebSocket Specialist | Connection management, scaling to 50K conn/server |
| Distributed Systems Engineer | CAP theorem decisions, consistency tradeoffs |
| Site Reliability Engineer | Chaos testing, SLO ownership, incident response |
