# 00 — Requirements Analysis: Chat Application (WhatsApp / Slack)

---

## Objective

Define the complete functional and non-functional requirements for a production-grade real-time chat platform supporting 1:1 messaging, group chats, presence tracking, message delivery guarantees, and multi-device synchronization. Establish capacity baselines before any architectural decision.

---

## Functional Requirements

### Core (Must Have)

| # | Requirement |
|---|-------------|
| F1 | Users can send and receive text messages in real time (1:1 conversations) |
| F2 | Users can create group conversations with up to 1,000 members |
| F3 | Messages are delivered with ordered guarantees within a conversation |
| F4 | Messages are persisted and retrievable (chat history with pagination) |
| F5 | System tracks message delivery states: **Sent → Delivered → Read** |
| F6 | Users can see real-time **presence status**: Online, Away, Offline |
| F7 | Users receive messages when offline via **push notifications** |
| F8 | Offline users receive all missed messages upon reconnection (offline sync) |
| F9 | Users can send media attachments: images, documents, audio |
| F10 | Users can search messages within a conversation |

### Extended (Should Have)

| # | Requirement |
|---|-------------|
| F11 | **Typing indicators**: sender typing state broadcast to recipients |
| F12 | **Read receipts**: per-member read timestamps visible to sender |
| F13 | **Multi-device sync**: same account logged in on phone + desktop + web — all receive messages |
| F14 | Message **reactions** (emoji reactions to a message) |
| F15 | Message **threading** (reply-to / thread in Slack style) |
| F16 | Users can **delete** a message (delete-for-me vs delete-for-everyone) |
| F17 | Users can **edit** a sent message (edit history preserved) |
| F18 | Channel-level notifications in group chats (mention @user, @here) |
| F19 | Link previews (unfurl URLs in messages) |
| F20 | Last seen timestamp per user |

### Advanced (Nice to Have)

| # | Requirement |
|---|-------------|
| F21 | End-to-end encryption (Signal protocol style) |
| F22 | Voice and video calls |
| F23 | Message pinning in channels |
| F24 | Bots / integrations (Slack App Platform equivalent) |
| F25 | Message translation |
| F26 | Scheduled messages |
| F27 | User analytics (message volume, active hours) |

---

## Non-Functional Requirements

| Category | Requirement |
|----------|-------------|
| **Availability** | 99.99% uptime for message delivery path — users expect chat to always work |
| **Latency** | Message delivery end-to-end (sender → recipient): p99 < 500ms for online users |
| **Latency** | Presence update propagation: p99 < 2 seconds |
| **Latency** | Chat history load (first page): p99 < 200ms |
| **Throughput** | Sustain 1 million messages/second at peak across all conversations |
| **Durability** | Zero message loss once the server acknowledges receipt |
| **Ordering** | Messages within a conversation must be delivered in send order |
| **Scalability** | Support 500 million registered users, 100 million DAU |
| **Consistency** | Eventual consistency acceptable for presence; strong ordering within a conversation |
| **Storage** | Messages retained indefinitely (WhatsApp model) or configurable (Slack 90-day free tier) |
| **Security** | All messages encrypted in transit (TLS) and at rest |
| **Multi-device** | A message sent on mobile appears on desktop within 2 seconds |

---

## Assumptions

- Users authenticate via a separate Auth Service (JWT tokens); Chat Service trusts validated tokens
- Media files are stored in object storage (S3-compatible); Chat Service stores only the CDN URL reference
- Push notification delivery to offline users is handled by a separate Notification Service (per design #3)
- Group size cap: 1,000 members for direct messaging groups; 10,000 for broadcast channels (Slack-style)
- Message IDs are globally unique and monotonically increasing within a conversation
- Device token management for push is handled by a Device Registry Service
- Link unfurling is a background job — messages are delivered immediately, preview appended asynchronously
- End-to-end encryption is out of scope for v1 but architecture must not preclude it

---

## Constraints

- WebSocket connections are stateful — connection server assignment must be sticky per session
- Fan-out for large groups (1,000 members) must not block real-time delivery to online members
- Storage schema must support time-range queries efficiently (pagination by timestamp)
- Typing indicator updates must NOT be persisted — ephemeral only
- Presence updates must not saturate the messaging pipeline

---

## Scale Estimation

### User and Traffic Base

| Metric | Estimate | Basis |
|--------|----------|-------|
| Registered users | 500 million | WhatsApp-scale target |
| Daily Active Users (DAU) | 100 million | 20% DAU ratio |
| Concurrent WebSocket connections | 50 million | 50% of DAU simultaneously connected |
| Average messages per user per day | 40 | Industry benchmark |
| Total messages per day | 100M DAU × 40 = **4 billion messages/day** |
| Messages per second (average) | 4B / 86,400 = **~46,000 msg/sec** |
| Messages per second (peak) | **~1,000,000 msg/sec** (evening peak × 20x) |
| Group messages (fanout) | 30% of all messages → up to 1,000 recipients each |
| Media uploads | ~10% of messages contain media = 400 million media/day |

### Connection Estimates

| Metric | Estimate |
|--------|----------|
| Concurrent WebSocket connections | 50 million |
| Connections per WebSocket server (16-core) | ~50,000 |
| WebSocket servers needed | 50M / 50K = **1,000 servers** |
| Heartbeat overhead per connection | 1 packet / 30 sec = ~1.7M heartbeats/sec |

### Storage Estimates

| Item | Size per Record | Daily Volume | Storage |
|------|----------------|-------------|---------|
| Message record (text) | ~1 KB (content + metadata) | 4 billion | **~4 TB/day** |
| Message record (media ref) | ~300 bytes (URL + metadata) | 400 million | ~120 GB/day |
| Media binary (S3) | avg 500 KB | 400 million | ~200 TB/day |
| Conversation metadata | ~500 bytes | Low churn | ~500 GB total |
| Presence state | ~200 bytes/user | 100M DAU | ~20 GB (Redis) |
| User device tokens | ~400 bytes/device × 2 devices | 500M users | ~400 GB |

**Message storage growth: ~4 TB/day → ~1.5 PB/year (text only)**

This immediately signals a need for:
1. Cassandra or a wide-column store (not PostgreSQL) for messages
2. Tiered storage (hot/warm/cold) with archival to object storage
3. Compression at the storage layer

### Bandwidth Estimates

| Direction | Estimate |
|-----------|----------|
| Inbound (message sends) | 46K msg/sec × 1 KB avg = **~46 MB/s** |
| Outbound (message delivery to recipients) | Fanout × 5 recipients avg = **~230 MB/s** baseline |
| Outbound (group fanout at peak) | 1M msg/sec × 1 KB = **1 GB/s** at peak |
| Media CDN outbound | 200 TB/day = **~2.3 GB/s** average |
| WebSocket heartbeats | 50M × 100 bytes / 30 sec = **~167 MB/s** |

### Back-of-Envelope Summary

```
Concurrent WebSocket connections:  50 million
WebSocket servers needed:          ~1,000 (at 50K conn/server)
Messages per second (peak):        ~1,000,000
Message storage growth:            ~4 TB/day (text) + 200 TB/day (media)
Presence updates per second:       ~500,000 (status changes during peak)
Kafka throughput needed:           ~1 GB/s at peak
Cassandra write throughput:        ~1M writes/sec (partitioned by conv_id)
Redis memory for presence:         ~20 GB
CDN bandwidth:                     ~2.3 GB/s average
```

---

## Read / Write Patterns

### Write-Heavy Paths

| Operation | Pattern |
|-----------|---------|
| Message send | Write to Cassandra (high write throughput) + Kafka publish |
| Presence update | Write to Redis (TTL-based) |
| Delivery receipt update | Write to Redis (hot) → async persist to Cassandra |
| Typing indicator | Ephemeral Redis write, NOT persisted |
| Media upload | Client → S3 direct upload, URL reference written to message store |

### Read-Heavy Paths

| Operation | Pattern |
|-----------|---------|
| Chat history (pagination) | Read from Cassandra by (conv_id, time_bucket) |
| Conversation list | Read from Redis cache (active convs per user) |
| Presence lookup | Read from Redis |
| Unread message count | Read from Redis counter |
| Message search | Read from Elasticsearch (separate index) |

### Hot Data vs Cold Data

| Data | Hot (< 7 days) | Warm (7–90 days) | Cold (> 90 days) |
|------|---------------|-----------------|-----------------|
| Messages | Cassandra (SSD) | Cassandra (lower tier) | S3 / Apache Iceberg |
| Media | CDN cache | S3 Standard | S3 Glacier |
| Presence | Redis | — | Discarded |
| Conversation metadata | Redis + PostgreSQL | PostgreSQL | PostgreSQL |

---

## Latency Expectations

| Operation | Target p50 | Target p99 |
|-----------|-----------|-----------|
| Message delivery (online sender → online recipient) | 50ms | 500ms |
| Message delivery (online → offline, push notification) | 200ms | 3s |
| Presence update propagation | 200ms | 2s |
| Chat history load (first 50 messages) | 20ms | 200ms |
| Typing indicator broadcast | 50ms | 300ms |
| Read receipt propagation | 100ms | 1s |
| Media upload pre-signed URL | 10ms | 50ms |
| Multi-device sync lag | 200ms | 2s |

---

## Availability Targets

| Scenario | Target |
|----------|--------|
| Message ingestion availability | 99.99% |
| Message delivery availability | 99.99% |
| Presence service availability | 99.9% (degraded presence OK) |
| Media upload/download | 99.9% |
| Chat history read | 99.9% |
| RTO (Recovery Time Objective) | < 30 seconds for message delivery |
| RPO (Recovery Point Objective) | Zero — no acknowledged message can be lost |

---

## Interview Discussion Points

- Why is Cassandra preferred over PostgreSQL for message storage at this scale?
- How do you guarantee ordering without a distributed lock on every write?
- How do you handle fan-out to a 1,000-member group without blocking real-time delivery?
- At 50 million concurrent WebSocket connections, how do you route a message to the right server?
- What happens when the presence service is down — how do you degrade gracefully?
- How do you sync messages across 3 devices for the same user with no duplicates?
- At 4 TB/day of message storage, when and how do you archive old messages?
- How do you prevent message loss when a client disconnects mid-send?
