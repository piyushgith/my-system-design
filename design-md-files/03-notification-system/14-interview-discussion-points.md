# 14 — Interview Discussion Points: Notification System

---

## Objective

This document prepares you for a candidate's system design interview on the Notification System. It contains the most likely follow-up questions, expected answers, tradeoff discussions, common candidate mistakes, and the differentiating points that separate senior from staff-level responses.

---

## Part 1: Core Design Questions

### Q: How would you design a notification system that supports email, SMS, and push?

**Junior answer** (avoid): "We'd have an API that calls SendGrid for email, Twilio for SMS, and FCM for push."

**Senior answer**: Focus on:
1. The async nature — producer doesn't wait for delivery
2. Kafka as the backbone (why: durability, replay, burst absorption)
3. Per-channel dispatcher isolation (why: email rate limits ≠ SMS rate limits)
4. Outbox pattern (why: atomic DB write + Kafka publish)
5. Preference engine (why: user choice must be respected before dispatching)

**Staff-level addition**: Discuss the priority lane problem — how do you ensure OTP delivery during a 50M campaign burst? Answer: separate Kafka topics or consumer groups per priority tier.

---

### Q: How do you guarantee a notification is delivered exactly once?

**The honest answer**: You cannot guarantee exactly-once delivery to external providers. What you can guarantee is:
- **At-least-once delivery from the system** (Outbox pattern + Kafka at-least-once + retries)
- **At-most-once delivery to the user** (idempotency key + application-layer deduplication)

The combination is "effectively exactly once" for most business requirements.

**Key distinction to make**:
- Idempotency key on the API: prevents the same notification_request from being processed twice
- `SET NX event_id` in dispatcher: prevents the same Kafka message from triggering two provider calls
- Provider idempotency: SendGrid supports an `X-Unique-Args` or message ID header — even if the dispatcher calls twice, SendGrid deduplicates

---

### Q: How do you handle user notification preferences?

**What interviewers want to see**:
1. Preference schema: channel × category matrix (not just "on/off")
2. Cache strategy: why you can't hit DB on every notification (50K RPS × preference lookup = DB killer)
3. Quiet hours: enforce in Fanout, check user timezone
4. Hard unsubscribe vs soft unsubscribe (legal/compliance distinction)
5. TRANSACTIONAL category should bypass most opt-outs (CAN-SPAM compliance)

**Tradeoff to discuss**: L1 cache TTL of 10 seconds means a user who opts out may receive one more notification within that window. Acceptable for marketing opt-outs, not acceptable for immediate opt-out in regulated contexts (GDPR erasure). Solution: forced cache invalidation via Kafka event for legal-grade opt-out.

---

### Q: What is the Outbox pattern and why is it needed here?

**The problem without Outbox**:
```
1. Write notification to PostgreSQL ✓
2. Crash here
3. Kafka publish never happens → notification silently lost
```

OR:
```
1. Publish to Kafka ✓
2. Crash here
3. DB write never happens → Kafka has event but DB has no record → inconsistency
```

**Outbox solution**: Both DB write and Kafka event are written in the same DB transaction. A relay process reads the outbox table and publishes to Kafka. If the relay crashes after Kafka publish but before DB update: re-publishes on next poll (consumers must be idempotent).

**Why not Kafka Transactions as an alternative?**
Kafka Transactions would require the notification API to begin a Kafka transaction before writing to DB and commit both atomically. This couples the application to Kafka's transaction coordinator — complex and adds latency. Outbox is simpler and battle-tested.

---

### Q: How does the system handle a provider being down for 2 hours?

**Expected answer structure**:
1. Circuit Breaker detects failure → opens (stops sending to provider)
2. Kafka accumulates messages (3-day retention → 2-hour outage is fully recoverable)
3. Fall back to secondary provider if configured (SES for email, SNS for SMS)
4. On recovery: circuit breaker half-opens → gradual traffic restoration
5. KEDA scales dispatchers to maximum → rapid drain of accumulated queue
6. Jitter on retries prevents thundering herd when provider re-opens

**What breaks?**: Marketing emails during the outage are delayed, not lost. OTP emails reroute to SMS via channel fallback. No data is lost.

---

## Part 2: Deep-Dive Follow-Ups

### Q: How do you handle sending to 50 million users in a batch campaign?

**Key points**:
- Campaign Fanout is separate from real-time Fanout (isolated scaling, isolated Kafka topic)
- Fanout is throttled (`throttle_rps = 5,000/sec`) to prevent Kafka saturation
- 50M users at 5,000/sec = 2.78 hours of fanout (acceptable for marketing)
- Template version pinned at campaign start — no content changes mid-campaign
- Progress tracking: `batch_campaigns.dispatched_count` incremented atomically

**What breaks at large scale?**
- If segment service takes 1 hour to resolve 50M user IDs, the fanout cannot start immediately → segment pre-computation is needed
- Provider rate limits: 50M emails at 36,000/sec = 23 minutes just to dispatch to SendGrid. Multiple sub-accounts needed.

---

### Q: How does multi-channel fallback work?

**Design**: After the main channel fails (e.g., push notification fails because device token expired):
1. `delivery.attempt.completed` event published with `status: FAILED`, `failure_code: DEVICE_TOKEN_EXPIRED`
2. Retry Orchestrator detects `DEVICE_TOKEN_EXPIRED` → not retriable for push
3. Checks notification's fallback policy (configured per category): `push → sms → email`
4. Publishes new `sms.dispatch.requested` event for the same notification_id

**What makes this hard**:
- Idempotency: the SMS dispatch is a new attempt on the same notification_id — must not be treated as duplicate
- Preference check: user may be opted in to push but opted out of SMS — fallback must still respect preferences
- Status tracking: notification status should be PARTIALLY_DELIVERED (push failed, SMS succeeded), not just DELIVERED

---

### Q: How would you add WhatsApp as a channel?

**This tests extensibility thinking**:
1. Add `WHATSAPP` to Channel enum in shared contracts library
2. Create `whatsapp.dispatch.requested` Kafka topic
3. Build new WhatsApp Dispatcher service (same pattern as SMS Dispatcher)
4. Update Fanout Service to route to WhatsApp topic based on preferences
5. Add preference UI for WhatsApp opt-in
6. Template updates: WhatsApp has different formatting (rich media, interactive buttons)

**What stays unchanged**: Notification API, Outbox pattern, Fanout routing logic, Delivery Log, Retry Orchestrator. This is the architectural payoff of per-channel isolation.

---

### Q: What would break first as scale grows?

| Scale Point | What Breaks First | Solution |
|-------------|------------------|---------|
| 1,000 RPS | Nothing breaks | Baseline works |
| 10,000 RPS | DB for preference lookups | Redis cache |
| 50,000 RPS | Kafka partitions too few | Add partitions, scale consumers |
| 100,000 RPS | PostgreSQL write throughput | Shard notifications table |
| 500,000 RPS | Redis single node | Redis Cluster with 10+ nodes |
| 1M RPS | Fanout Service CPU | 100+ fanout pods, dedicate more Kafka partitions |

---

## Part 3: Scaling and Tradeoff Questions

### Q: Why Kafka over SQS for this use case?

| Feature | Kafka | SQS |
|---------|-------|-----|
| Message replay | Yes (7-day retention) | No (deleted on consume) |
| Consumer groups | Yes (multiple independent consumers) | Limited (one queue = one consumer type) |
| Ordering | Per partition | FIFO queues only |
| Throughput | 50K+ msg/sec per partition | ~3,000 msg/sec per queue |
| Delay/retry topics | Custom topology supported | 15-minute max visibility timeout |
| Exactly-once | Kafka Transactions | No |

**Kafka wins for this use case** because: replay capability for failure recovery, consumer group model for multiple downstream consumers (Fanout, Delivery Log), and the throughput required for batch campaigns.

---

### Q: How do you handle duplicate delivery to a user?

**Scenario**: Outbox relay crashes after publishing to Kafka, before marking event PUBLISHED. On restart, it re-publishes the same notification.

**Defense layers**:
1. Fanout Service: `SET NX fanout_processed:{notification_id}` — only processes each notification_id once
2. Dispatcher: `SET NX dispatch_processed:{attempt_id}` — only calls provider once per attempt_id
3. Provider: SendGrid supports message deduplication via `X-Unique-Args` header

**The honest boundary**: if the provider deduplication fails, the user may receive two identical emails. This is an acceptable rare case for most notification types, but unacceptable for OTP (would just be confusing, not harmful). For OTP, the producer should set a short `expires_at` and send only one at a time.

---

### Q: CAP theorem — what consistency model does this system choose?

**This system is AP (Available + Partition Tolerant) at the delivery layer**:
- Notification delivery continues even if the Preference Service is temporarily unavailable (fall back to last-known preference from cache or "deliver to all channels")
- An eventually consistent preference (updated preference takes effect within minutes) is acceptable

**CP at the submission layer**:
- The Notification API uses Outbox with synchronous DB write → strong consistency for "was this notification accepted?"
- Idempotency key check uses Redis → if Redis is partitioned, falls back to DB (slower but consistent)

**The tradeoff to articulate**: we choose availability of delivery over strict consistency of preference enforcement. A user who opts out of marketing emails may receive one more email within the cache TTL window. This is a business tradeoff accepted by the legal team, not a technical shortcut.

---

## Part 4: Common Mistakes Candidates Make

| Mistake | Why It's Wrong | Better Answer |
|---------|---------------|--------------|
| "Call SendGrid directly from the API" | Couples producer to provider, blocks on network | Async via Kafka + dispatcher |
| "Use a single DB table for notifications + delivery logs" | 100M row table with heavy writes + reads → contention | Separate tables, separate partitions, separate stores |
| "Use Redis for all retries" | Redis has limited durability; retries on long timescales need persistent storage | Kafka retry topics or Redis + PostgreSQL backup |
| "Same Kafka topic for all channels" | Email backpressure blocks SMS delivery | Per-channel topics with independent consumers |
| "Template rendered in Notification API" | Couples the fast ingestion path to rendering logic; validation issues | Render at dispatch time in dispatcher |
| "Store email content forever for audit" | PII retention violation (GDPR) + storage cost | 90-day hot retention, purge PII columns |
| "One notification = one delivery attempt" | Ignores retries, multi-channel, multi-device | Delivery attempt is a separate entity (one notification → many attempts) |

---

## Part 5: Staff Engineer Differentiators

### Discuss the Priority Lane Problem Proactively

Before being asked: "One thing I'd want to design carefully is what happens when a 50M campaign fires while users are authenticating with OTPs. Both go through the same Fanout Service. At 50,000/sec campaign load, OTP notifications would queue behind millions of campaign messages. I'd separate these into dedicated Kafka topics with dedicated consumer groups, giving transactional notifications their own isolated processing lane."

### Discuss Operational Complexity Cost

"The retry topic topology I've designed (5 retry topics per channel × 3 channels = 15 extra Kafka topics) adds operational overhead. The operations team needs to monitor 25+ topics, DLQ dashboards, and circuit breaker states. At a startup with 2 engineers, this is overengineering. I'd start with a single retry topic and Redis-based backoff, and evolve to this topology only when the failure volume justifies it."

### Discuss the "What Breaks First" Progression

Show awareness of scaling inflection points (see Part 2 above). Interviewers at Taking level want to see that you've thought beyond the current scale.

### Compliance Awareness

"An important constraint I haven't mentioned yet: India's TRAI DND registry requires checking before sending marketing SMS to any Indian phone number. This adds a DND verification step in the SMS Dispatcher — we'd need to sync the DND registry daily and check before every SMS dispatch. This is a compliance requirement, not a nice-to-have, and failing it exposes the company to regulatory fines."

---

## Part 6: Architecture Self-Critique

### Weaknesses of This Design

| Weakness | Impact | Mitigation |
|----------|--------|-----------|
| High operational complexity (15+ services, 25+ Kafka topics) | Significant DevOps investment | Start as monolith, evolve gradually |
| Preference cache staleness (10 second window) | Marketing opt-out delay | Acceptable per CAN-SPAM; cache invalidation for GDPR cases |
| Fanout at 50K/sec for campaigns | Kafka producer saturation | Campaign throttle rate + separate fanout service |
| ClickHouse is a new technology for most teams | Learning curve, separate ops | Alternative: PostgreSQL with TimescaleDB for analytics at smaller scale |
| Retry topic proliferation | 15 extra topics per added channel | Redis sorted set as simpler alternative for retries |
| Cross-region idempotency not solved | Possible duplicate delivery if user switches region | Acceptable for marketing; solve with global Redis for transactional |

### When This Architecture Is Overkill

**For a startup** (< 100K users, < 10K notifications/day):
- Single Spring Boot monolith with async threads
- Direct provider calls with Spring Retry
- PostgreSQL single table for all notification state
- No Kafka, no Redis cache, no distributed dispatchers

**Introduce Kafka when**: delivery volume exceeds 1,000/sec OR a provider outage causes data loss

**Introduce Redis when**: preference/template lookups start appearing in slow query logs

---

## Expected Interviewer Probing Questions

1. "You mentioned Outbox pattern — what if the relay falls behind by 10 minutes? How do you detect and recover?"
2. "Your circuit breaker opens when SendGrid fails. What's your fallback for users who only have email configured?"
3. "How do you test this system end-to-end in a staging environment without sending real emails to real users?"
4. "Your SLO says 99.5% of transactional notifications within 5 seconds. How do you measure this?"
5. "A producer is sending a notification with incorrect template variables — the render fails. What happens to the notification?"
6. "You have 60 email dispatcher pods consuming from 60 Kafka partitions. One pod is slow (provider timeout). Does it block other pods?"
7. "How does your system handle a user who receives 100 notifications in 1 second from a buggy producer service?"
