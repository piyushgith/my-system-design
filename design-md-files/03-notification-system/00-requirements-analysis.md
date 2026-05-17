# 00 — Requirements Analysis: Notification System

---

## Objective

Define the complete functional and non-functional requirements for a production-grade centralized notification platform supporting email, SMS, push, and in-app channels. Establish scale baselines and capacity planning before any architectural decision.

---

## Functional Requirements

### Core (Must Have)

| # | Requirement |
|---|-------------|
| F1 | Producers (other services or internal teams) can send a notification to one or more users by calling a single API |
| F2 | System supports four channels: **Email**, **SMS**, **Push (mobile)**, **In-App** |
| F3 | Each notification must be delivered to the user's preferred channel(s) |
| F4 | Users can configure per-channel opt-in/opt-out preferences |
| F5 | Users can configure per-notification-category preferences (e.g., Marketing off, Transactional always on) |
| F6 | System supports message templates with variable substitution (e.g., `Hello {{first_name}}`) |
| F7 | Templates can be managed (create, update, version) by operators independently of deployments |
| F8 | Each notification delivery attempt must be logged with timestamp, status, channel, and provider response |
| F9 | System must retry failed deliveries with exponential backoff |
| F10 | System must support idempotent notification submission (same request sent twice → delivered once) |

### Extended (Should Have)

| # | Requirement |
|---|-------------|
| F11 | Multi-channel fallback: if push delivery fails, fall back to SMS or email |
| F12 | Scheduled notifications: deliver at a specific future timestamp |
| F13 | Batch/bulk notifications: one trigger sends to a cohort of users (e.g., marketing campaigns to 1M users) |
| F14 | In-app notification inbox with read/unread status |
| F15 | Notification priority levels: Critical, High, Normal, Low — affect queue priority and retry behavior |
| F16 | Delivery status callbacks / webhooks for producers who need async status |
| F17 | User-level quiet hours / Do Not Disturb windows |

### Advanced (Nice to Have)

| # | Requirement |
|---|-------------|
| F18 | Optimal send time — ML-based or rule-based decision for best delivery window |
| F19 | A/B testing for notification templates |
| F20 | Analytics dashboard: open rates, click rates, delivery success per channel |
| F21 | Dynamic channel selection based on user engagement history |
| F22 | Real-time delivery status streaming to producers |

---

## Non-Functional Requirements

| Category | Requirement |
|----------|-------------|
| **Availability** | 99.9% uptime for API ingestion; 99.95% for transactional channels (OTP, critical alerts) |
| **Throughput** | Sustain 50,000 notifications/second at peak (batch campaigns); 5,000/sec baseline |
| **Latency** | Transactional notifications (OTP, password reset): p99 delivered within 5 seconds of API call |
| **Latency** | Marketing batch: best-effort, delivery window up to 30 minutes acceptable |
| **Durability** | No notification lost once accepted by the API (at-least-once delivery guarantee) |
| **Scalability** | Scale horizontally per channel; email workload should not affect SMS workload |
| **Idempotency** | Same notification ID submitted twice must not result in duplicate delivery |
| **Observability** | Per-channel delivery rate, failure rate, DLQ depth must be visible in real time |
| **Compliance** | PII in notification content must be masked in logs; CAN-SPAM / GDPR unsubscribe honored |

---

## Assumptions

- Notification content is trusted — producers are internal services; no public-facing submission API
- Providers: SendGrid (email), Twilio (SMS), Firebase FCM (push), internal WebSocket/DB (in-app)
- User device token management (push) is handled by a separate Device Registry service; Notification System calls it
- Template rendering happens inside the Notification System before dispatching to external providers
- In-app notifications are stored in the database and polled/streamed by frontend clients
- SMS OTP use case is the highest-priority, lowest-latency path; everything else is best-effort
- A user may have multiple devices for push notifications; system must fan out to all active tokens
- Soft unsubscribe (user preference) is enforced by Notification System; hard unsubscribe (provider-level) is mirrored back

---

## Constraints

- Cannot store raw notification content (PII like OTP values) permanently — must purge after TTL
- External provider rate limits must be respected (SendGrid: 600 emails/sec, Twilio: varies by account tier)
- SMS delivery cannot exceed regulatory limits in certain countries (e.g., India DND registry check)
- Must not deliver marketing notifications during user-configured quiet hours
- Idempotency keys must expire after 24 hours to avoid unbounded storage growth

---

## Scale Estimation

### User and Traffic Base

| Metric | Estimate | Basis |
|--------|----------|-------|
| Total registered users | 100 million | Mid-size platform scale |
| Daily Active Users (DAU) | 20 million | 20% DAU/MAU ratio |
| Transactional notifications/day | 5 million | OTP, order updates, alerts |
| Marketing notifications/day | 50 million | Batch campaigns to 50% of users |
| Total notifications/day | ~55 million | Combined |
| Notifications/second (avg) | ~640/sec | 55M / 86,400 |
| Notifications/second (peak, batch) | ~50,000/sec | Campaign bursts |
| Delivery attempts/day | ~100 million | ~2x notifications (multi-channel + retries) |

### Per-Channel Volume Distribution

| Channel | % of Volume | Daily Volume | Peak RPS |
|---------|-------------|-------------|----------|
| Email | 50% | 27.5 million | 25,000/sec |
| Push | 35% | 19.25 million | 18,000/sec |
| SMS | 10% | 5.5 million | 5,000/sec |
| In-App | 5% | 2.75 million | 2,500/sec |

### Storage Estimates

| Item | Estimate |
|------|----------|
| Notification record (metadata only, no content) | ~1 KB |
| Delivery attempt log per attempt | ~500 bytes |
| Daily metadata storage | 55M × 1KB = **55 GB/day** |
| Daily delivery log storage | 100M × 500B = **50 GB/day** |
| Total daily storage | ~105 GB/day |
| Monthly storage (raw) | ~3 TB/month |
| After 90-day retention with archival | ~9 TB hot, rest archived |
| Template store | ~10 MB (small, mostly cached) |
| User preference store | 100M users × 200 bytes = **~20 GB** |
| In-app inbox (30-day unread retention) | ~50 GB |
| Redis for idempotency keys (24h TTL) | ~5 GB |

### Bandwidth Estimates

| Direction | Estimate |
|-----------|----------|
| API ingest (internal, 50K RPS × 2KB avg payload) | ~100 MB/s peak |
| Outbound to email provider (50K × 5KB email) | ~250 MB/s peak |
| Outbound to SMS provider (5K × 500B) | ~2.5 MB/s |
| Outbound push (18K × 1KB) | ~18 MB/s |

---

## Read / Write Patterns

### Write-Heavy Paths
- Notification submission API: insert to notification table + publish to Kafka (write-dominant)
- Delivery attempt logging: extremely high write volume (100M rows/day)
- In-app inbox: insert per delivered in-app notification

### Read-Heavy Paths
- User preference lookup: read on every notification dispatch (cache mandatory)
- Template lookup: read on every render (cache mandatory)
- In-app inbox: user polls their inbox (paginated reads)
- Analytics queries: aggregate reads on delivery log (separate OLAP store)

### Hot Data vs Cold Data
| Data | Hot (< 1 day) | Warm (1–30 days) | Cold (> 30 days) |
|------|--------------|-----------------|-----------------|
| Notification metadata | PostgreSQL + Redis | PostgreSQL | Archive / S3 |
| Delivery attempts | PostgreSQL (partitioned) | PostgreSQL | Archive |
| In-app inbox | PostgreSQL + Redis | PostgreSQL | Deleted |
| Analytics | Real-time aggregation | ClickHouse | S3 Parquet |

---

## Latency Expectations

| Operation | Target p50 | Target p99 |
|-----------|-----------|-----------|
| API: Accept notification (enqueue) | 5ms | 30ms |
| Transactional (OTP) end-to-end delivery | 1s | 5s |
| Marketing email end-to-end | best-effort, minutes | 30 min |
| Push notification delivery | 500ms | 3s |
| SMS delivery | 2s | 8s |
| In-app notification visible | 200ms | 1s |
| Template render | 2ms | 10ms |
| Preference lookup (cache hit) | 0.5ms | 2ms |

---

## Availability Targets

| Scenario | Target |
|----------|--------|
| API ingestion availability | 99.9% |
| Transactional channel delivery | 99.9% |
| Marketing channel delivery | 99.5% (batch, not time-critical) |
| RTO (Recovery Time Objective) | < 15 minutes |
| RPO (Recovery Point Objective) | < 1 minute (Kafka replay covers gap) |
| DLQ processing SLA | Within 1 hour of failure |

---

## Back-of-Envelope Summary

```
API ingest:          ~640 RPS avg, ~50,000 RPS peak (batch burst)
Delivery attempts:   ~100 million/day = ~1,157 attempts/sec avg
Storage growth:      ~3 TB/month (hot), archival after 90 days
Cache needed:        ~25 GB Redis (preferences + idempotency + templates)
DB size:             ~100 GB PostgreSQL hot data (partitioned)
Kafka throughput:    50,000 msg/sec at peak → 6 partitions per topic minimum
Provider limits:     SendGrid 600/sec → batching + multiple accounts
SMS:                 5,000/sec peak → Twilio pool required
```

**Key Insight:** This is a write-dominated, throughput-bound system with extreme burst characteristics. The engineering challenges are:
1. Absorbing batch campaign bursts (50K RPS) without cascading failures on transactional path
2. Enforcing provider rate limits without creating back-pressure on Kafka consumers
3. Idempotency at scale across retries without distributed locking overhead
4. Preference and template lookup being on the critical path for every delivery
5. Fan-out to multiple devices per user at push notification scale

---

## Interview Discussion Points

- How do you protect transactional (OTP) SLA when a 50M-user campaign fires simultaneously?
- At what volume does a single PostgreSQL instance for delivery logs break down?
- How do you guarantee idempotency when retrying across different Kafka consumer instances?
- What happens if SendGrid is down for 2 hours? How do you recover without losing 50M emails?
- How do you handle a user who unsubscribed at the provider level (hard bounce) vs at the preference level?
- What's your strategy for international SMS compliance (DND registries, local regulations)?
