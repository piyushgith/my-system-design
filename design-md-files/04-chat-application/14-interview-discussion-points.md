# 14 — Interview Discussion Points: Chat Application

---

## Objective

Prepare for FAANG-level system design interviews by anticipating every follow-up question, tradeoff discussion, and deep-dive a senior or staff engineer interviewer will probe. This is the document to review the night before an interview.

---

## The Core Interview Flow

A FAANG interviewer follows a predictable pattern for chat:

```
1. Requirements clarification (5 min)
2. Back-of-envelope estimates (5 min)
3. High-level design (10 min)
4. Deep dive on 1-2 areas (20 min)
5. Scaling and failure discussion (10 min)
```

The deep dives are almost always:
- WebSocket connection management at scale
- Message delivery guarantees and ordering
- Fan-out for group messages
- Presence tracking at scale

---

## Section 1: Requirements Clarification

**Questions you should ask the interviewer:**

| Question | Why You Ask It |
|----------|---------------|
| Is this WhatsApp (1:1 + groups) or Slack (channels + threads)? | Slack's channel model changes fan-out dramatically |
| What is the expected scale — MAU, DAU, messages/day? | Drives every capacity decision |
| Do we need end-to-end encryption? | Changes storage model completely |
| What is the max group size? | Determines fan-out strategy |
| Do we need voice/video calls? | Completely separate subsystem; descope if needed |
| Should messages be retained forever or with a TTL? | Drives storage architecture |
| Multi-device support required? | Adds device registry + sync complexity |
| Is this a global product (multi-region)? | Adds multi-region replication complexity |

---

## Section 2: Key Numbers to Know

Memorize these for the whiteboard:

```
Scale target: 100M DAU, 40 messages/user/day = 4 billion messages/day
Messages per second (avg): 4B / 86,400 ≈ 46,000 msg/sec
Messages per second (peak): ~10× avg = 460,000 msg/sec (round to 500K)
Concurrent WebSocket connections: 50M (50% of DAU simultaneously connected)
WS connections per server: 50,000 (with Java 21 virtual threads)
WS servers needed: 50M / 50K = 1,000 servers
Storage: ~1 KB/message × 4B/day = 4 TB/day
Group fan-out: 30% of messages × 500 avg recipients = 6 billion fan-out deliveries/day
```

---

## Section 3: Most Common Interviewer Follow-Ups

### "How do you deliver a message to a user when there are 1,000 WebSocket servers?"

**Answer**: Two-layer routing.
1. **Connection Registry** in Redis: `user:{userId} → serverId` — tells us which server holds the user's connection
2. **Redis Pub/Sub**: Fan-Out Service publishes to `ws:server:{serverId}` channel; that server delivers to the user

"If the user is not connected, Fall-Out publishes to Notification Service via Kafka for push notification."

**Follow-up**: "What if Redis Connection Registry is down?"
- Short answer: Fan-Out can't route → falls back to push notification for all recipients
- Better: Circuit breaker + fallback to Notification Service for the window Redis is down

---

### "How do you guarantee message ordering?"

**Answer**: Server-assigned monotonic sequence numbers per conversation.

1. `INCR seq:{convId}` in Redis → atomic, monotonic counter
2. Sequence number is written to Cassandra alongside the message
3. Clients always sort by `sequence_num`, not by client timestamp
4. All events for a conversation are on the same Kafka partition (partitioned by `conv_id`) → in-order processing

**Why not timestamps?**
"Clock skew between servers can be ±100ms. Two messages sent in the same millisecond have ambiguous ordering. Clients can't be trusted to set the time. Server-assigned sequence numbers are the canonical ordering authority."

**Follow-up**: "What if the Redis sequence counter is unavailable?"
Fallback: `SELECT MAX(sequence_num) FROM messages WHERE conv_id = ? AND time_bucket = CURRENT_MONTH` from Cassandra. More expensive (5–10ms vs 0.5ms) but safe. Gaps in sequence numbers are possible during the fallback window — clients handle gaps gracefully.

---

### "Why Cassandra instead of PostgreSQL for messages?"

**Answer framework** (say these in order):

1. **Scale**: 4 TB/day of messages. PostgreSQL's maximum write throughput on a single node is ~10–20K writes/sec. We need 1M writes/sec at peak.

2. **Write pattern**: Messages are append-only (very rarely updated, never deleted hard). Cassandra is optimized for exactly this: high-throughput sequential writes.

3. **Access pattern**: We always fetch messages by `(conversation_id, time_range)`. Cassandra's partition key = `(conv_id, time_bucket)` collocates all messages for a conversation on the same node — efficient range scans.

4. **Horizontal scale**: Adding Cassandra nodes adds linear write and read throughput. PostgreSQL requires sharding (complex) to scale horizontally.

5. **When PostgreSQL is better**: Conversation metadata, membership, user profiles — relational data with few writes, joins, foreign keys. We use PostgreSQL for exactly that.

"The rule is: write-heavy append-only time-series → Cassandra. Relational, transactional, low-write → PostgreSQL."

---

### "How do you handle typing indicators without overloading the system?"

**Answer**:

1. Typing indicators are **ephemeral** — never persisted to any database
2. Client sends TYPING frame → WS Server → Presence Service → Redis `SET typing:{conv}:{user} 1 EX 5`
3. Presence Service publishes to **Redis Pub/Sub** (NOT Kafka) → WS Servers of online recipients
4. TTL = 5 seconds: if user stops typing and doesn't send a "stopped" signal, the key expires → auto-clear

**Why not Kafka?**
"Kafka has 10–50ms latency from publish to consume. Typing indicators need < 100ms round-trip or they look laggy. Redis Pub/Sub is < 1ms. Also, if you persisted typing events to Kafka, you'd produce billions of ephemeral events with no value."

**Rate limiting**: Client sends typing events at most every 1 second (debounced at client). Otherwise, holding backspace generates a TYPING event on every keystroke.

---

### "How do you handle offline users receiving messages?"

**Answer** (full flow):

1. Fan-Out Service checks Connection Registry for recipient → no active connection
2. Publishes `offline.notification` event to Kafka
3. Notification Service consumes → fetches push token from Device Registry → calls FCM/APNs
4. User gets push notification: "Alice: Hey, are you there?"
5. On reconnect, client sends `last_sync_seq` per conversation
6. WS Server responds with `SYNC_REQUIRED` + missed message count
7. Client fetches missed messages via REST API (`GET /messages?after_seq=N`)
8. Client sends bulk MARK_DELIVERED acknowledgment

**Key insight**: "We never lose messages because Cassandra is the source of truth. Real-time delivery is best-effort (Redis Pub/Sub). Durable delivery is guaranteed (Cassandra + offline sync)."

---

### "How do you sync messages across multiple devices?"

**Answer**:

1. Connection Registry maps userId → { deviceId: serverId } (multiple entries per user)
2. Fan-Out delivers to ALL device entries for the recipient, not just one
3. When user reads on one device, that device sends `MARK_READ {conv, seq}`
4. Fan-Out publishes read event to the user's OTHER device connections → those devices clear their unread badge

"Each device maintains a `last_sync_seq` per conversation. On reconnect, it fetches delta from the server. Devices never rely on in-memory state for persistence — Cassandra is the source of truth."

---

### "What breaks first as you scale to 1 billion users?"

**Answer** (rank by failure point):

| Component | Fails At | Why |
|-----------|---------|-----|
| Connection Registry (Redis single key per user) | ~500M users | Hash map in Redis → memory pressure per hot user |
| Fan-Out for large groups | 10K-member groups | O(members) operations per message → exponential |
| Cassandra partition size | After ~5 years per conversation | time_bucket solves this — new concern is SSTable compaction at node scale |
| Kafka partition count | When partitions > 200K | Kafka controller overhead with too many partitions |
| PostgreSQL for conversation metadata | ~100M conversations | Needs sharding at this point |

"The Fan-Out and Connection Registry are the earliest bottlenecks. The solution is server-level channels (publish to WS server, not to each user) — reduces fan-out from O(members) to O(servers), which is bounded and small."

---

## Section 4: Staff Engineer Discussion Points

### "What would you do differently if you had 5 engineers vs 500?"

| 5 Engineers (Startup) | 500 Engineers (FAANG) |
|----------------------|----------------------|
| Single Spring Boot app | 10+ microservices |
| PostgreSQL for everything | Cassandra + PostgreSQL + Redis |
| Redis Pub/Sub only (no Kafka) | Kafka + Redis Pub/Sub |
| Simple polling for offline sync | Event-driven offline sync |
| No E2EE | Signal protocol E2EE |
| No multi-region | Active-active multi-region |
| Deploy to a single VPC | Multi-region Kubernetes |

"The modular monolith path: start with separate Java packages for each domain (messaging, presence, conversation), share a database. Extract into separate services when you hit scaling limits on a specific component."

---

### "How does WhatsApp handle end-to-end encryption at scale?"

WhatsApp uses the Signal Protocol:
- Each device generates: identity key pair, signed prekey, one-time prekeys
- Server stores PUBLIC keys only in the Key Distribution Service
- When Alice messages Bob: Alice fetches Bob's public keys → X3DH key agreement → derives shared secret → encrypts message
- Server stores and routes opaque ciphertext — has NO knowledge of content
- Multi-device: separate encrypted session per device (not shared key)

**Tradeoffs of E2EE**:
- Server-side search impossible (can't index ciphertext)
- Server-side moderation impossible (can't detect CSAM without client-side scanning)
- Backup complexity: encrypted backups require key management
- Multi-device sync requires "message keys" that must be synchronized

---

### "How do you handle 'hot' conversations (e.g., a Slack channel with 50K messages/hour)?"

A hot conversation creates:
- One Cassandra partition with very high write rate → hot partition
- One Kafka partition with very high event rate → single consumer bottleneck

**Mitigations**:
1. **time_bucket partitioning** in Cassandra: limits partition size to 1 month of messages
2. **Kafka sub-partitioning**: For a single hot conversation, route messages to multiple partitions using `{conv_id}:{sub_shard}` as the key. Fan-Out must handle out-of-order delivery across sub-shards.
3. **Fan-out lazy approach**: Don't push every message to every subscriber in real-time. Use a conversation-level "new messages available" event; clients pull on demand.

---

### "What is the difference between 'delivered' and 'received' for a message?"

This is a nuanced question:
- **SENT**: Server wrote message to Cassandra (durable)
- **DELIVERED**: Recipient device received the message via WebSocket (or offline sync)
- **READ**: Recipient application opened the message (user saw it)

The gap between DELIVERED and READ:
- A push notification wakes the phone, system delivers to OS — this is DELIVERED to the device but the user may not have READ it
- WhatsApp shows "delivered" (double gray tick) when the device receives it, "read" (double blue tick) when the chat is opened

For 1:1 chats: show per-message receipts
For group chats: show aggregate (e.g., "500 of 1,000 delivered") — per-member receipt detail is a premium feature (Slack paid tiers)

---

## Section 5: Common Mistakes Candidates Make

| Mistake | What to Say Instead |
|---------|-------------------|
| "We'll use PostgreSQL for messages" | "PostgreSQL can't handle 1M writes/sec; we need Cassandra's linear write scale" |
| "We'll use WebSockets for everything including push" | "Push notifications need FCM/APNs for mobile battery and background delivery" |
| "We'll use a single Kafka topic" | "Per-channel topics isolate backpressure; SMS slowness doesn't block presence events" |
| "Ordering guaranteed by timestamp" | "Clock skew in distributed systems makes timestamps unreliable for ordering" |
| "We'll start with microservices" | "Start with a modular monolith; extract services when you hit specific bottlenecks" |
| "Redis Pub/Sub can handle 1M messages/sec" | "True for simple delivery, but connection registry design must avoid O(members) subscriptions per server" |
| "We need 100% uptime for everything" | "Presence can be eventually consistent; only message delivery needs 99.99%" |

---

## Section 6: "What Would Break First?" Analysis

**Interviewer's favorite final question.** Have a confident answer:

1. **Fan-Out Service** — at 1M group messages/sec × 1,000 members = 1 billion deliveries/sec. This is the first component to need radical optimization (server-level channels, lazy fan-out).

2. **Connection Registry (Redis)** — 50M entries, refreshed every 90 seconds. At extreme scale, Redis cluster sharding must be carefully sized. Hot users (celebrities in group chats) create hot Redis keys.

3. **Cassandra Sequence Counter (Redis)** — INCR for active conversations. A single active conversation can saturate a Redis key at 1,000 writes/sec (if messages pour in). Mitigated by Redis Cluster + pipeline batching.

4. **Kafka Partition Count** — 1,000 partitions for `message.created` seems like a lot but at 1B msg/sec it becomes inadequate. Kafka has limits on partition count per cluster (practical ceiling: ~1M partitions, but operational complexity rises steeply above 100K).

5. **Cassandra Repair Time** — with 60+ nodes storing PB of data, nodetool repair becomes days-long. Anti-entropy repair is essential but painful at scale. This is where operational complexity bites you.

---

## Section 7: Production Thinking Questions

| Question | Strong Answer |
|----------|-------------|
| "How do you test WebSocket servers at scale?" | Load test with 50K simulated connections per server using Gatling/k6. Run chaos: kill a server mid-test, verify client reconnect. |
| "How do you deploy 1,000 WS servers without downtime?" | Blue-green with connection drain. New pods start, old pods drain. 10-minute window is acceptable for planned maintenance. |
| "How do you debug why one user's messages are slow?" | Trace ID from client → search Jaeger by user_id + trace_id → see every span. Likely culprits: Redis INCR latency, Cassandra QUORUM slow node, Fan-Out consumer lag. |
| "How do you detect message delivery failures at scale?" | DLQ depth metric + alert. Fan-Out writes to DLQ when retries exhausted. Support team reads DLQ for affected user + message IDs. |
| "What happens during a Kubernetes pod disruption budget violation?" | If too many WS pods go down simultaneously, messages for those users route to Notification Service (offline push). PDB prevents Kubernetes from taking down more than 10% of WS pods at once. |
