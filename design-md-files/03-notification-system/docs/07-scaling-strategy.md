# 07 — Scaling Strategy: Notification System

---

## Objective

Define how each component of the Notification System scales from MVP (640 RPS) to peak campaign load (50,000 RPS), identify bottlenecks at each scale tier, and describe the horizontal and vertical scaling levers available.

---

## Scale Tiers

| Tier | RPS | Users | Architecture |
|------|-----|-------|-------------|
| Startup | < 100/sec | < 1M users | Modular Monolith, single DB, no Kafka |
| Growth | 100–5,000/sec | 1M–20M users | Kafka backbone, per-channel dispatchers, Redis |
| Scale | 5,000–50,000/sec | 20M–100M users | Sharded dispatchers, ClickHouse, batch campaign throttling |
| Hyperscale | > 50,000/sec | > 100M users | Multi-region, global preference store, ML-assisted routing |

---

## Ingestion Layer Scaling

### Notification API

**Bottleneck:** CPU for validation + DB write for Outbox
- Each API instance handles ~500 RPS (light validation + DB write)
- At 50,000 RPS peak: 100 API instances required
- Scale: horizontal behind a load balancer
- Stateless → auto-scaling with Kubernetes HPA (CPU threshold: 70%)
- Idempotency check: Redis SET NX — O(1), ~0.1ms, not a bottleneck

### Outbox Relay

**Bottleneck:** PostgreSQL scan for PENDING outbox events
- Relay uses partial index `idx_outbox_pending` → scan is always small
- At 50K submissions/sec, relay must process 50K events/sec → batch publish to Kafka in groups of 500
- Scale: 5-10 relay instances reading from PostgreSQL, with row-level locking (SELECT FOR UPDATE SKIP LOCKED) to prevent double-publishing
- `SKIP LOCKED` is the critical PostgreSQL feature that enables multi-instance relay without coordination overhead

```
SELECT * FROM outbox_events
WHERE status = 'PENDING'
ORDER BY created_at
LIMIT 500
FOR UPDATE SKIP LOCKED;
```

---

## Fanout Service Scaling

### Single-User Fanout (Transactional)
- CPU-bound: preference lookup (Redis, ~0.5ms) + 4 Kafka publishes
- Each Fanout instance handles ~2,000 notifications/sec
- At 50K RPS: 25 Fanout instances
- Scale: horizontal, add partitions to `notification.request.submitted` as instances grow

### Campaign Fanout (50M users)
- The critical scaling challenge: must produce 50M Kafka messages in reasonable time
- Rate: throttled to `throttle_rps` (default 5,000 user/sec)
- At 5,000/sec: 50M users take ~2.78 hours to fanout
- At 50,000/sec: ~16 minutes (but this saturates downstream dispatchers)
- **Solution:** Separate Campaign Fanout Service from real-time Fanout Service
  - Campaign Fanout runs at its own rate, isolated from transactional path
  - Real-time `notification.request.submitted` topic gets priority processing

### The "Celebrity Problem" for Notifications
If a single user receives notifications from 10,000 event sources simultaneously (system-generated alerts), fan-out can overwhelm their partition. Mitigation:
- Per-user rate limiting at Fanout level (skip/coalesce duplicate category notifications within 1 minute)
- Notification deduplication window: if same category delivered to same user within 60 seconds, drop subsequent

---

## Per-Channel Dispatcher Scaling

### Email Dispatcher

**Bottleneck:** SendGrid rate limit (600 emails/sec per API key)
- Scaling approach: **Multiple SendGrid API keys / sub-accounts**
- Each Email Dispatcher instance has a dedicated API key
- 60 partitions × 60 instances × 600 email/sec = 36,000 emails/sec maximum
- At peak 25,000 email/sec: 42 instances consuming from 60 partitions
- **Provider-level back-pressure:** If SendGrid returns 429, dispatcher parks the message back to a retry topic (not Kafka pause — that blocks the whole partition)

### SMS Dispatcher

**Bottleneck:** Twilio rate limits vary by account tier (~100–1,000 SMS/sec)
- SMS is the lowest-volume, highest-cost channel — prioritize delivery reliability over throughput
- Use Twilio's Message Services (pools of numbers) for higher throughput
- 30 partitions × 30 instances × 100 SMS/sec = 3,000 SMS/sec
- International SMS: separate dispatcher instances per region to comply with local routing requirements

### Push Dispatcher

**Bottleneck:** FCM batch API — supports up to 500 messages per request
- Push Dispatcher uses FCM's batch endpoint (500 tokens per API call)
- A user with 3 devices triggers 1 batch call covering all 3 tokens
- At 18,000 push/sec: 36 tokens/batch call → 500 batch calls/sec → manageable
- APNs (iOS): separate HTTP/2 connection pool; APNs supports parallel connections

### In-App Dispatcher

**Bottleneck:** PostgreSQL write throughput for `in_app_inbox` inserts
- No external provider calls — purely DB writes
- Batch inserts: collect 100 in-app dispatch events and INSERT in a single statement
- At 2,500 in-app/sec: 25 batch inserts/sec of 100 rows each → ~2.5 MB/sec write throughput (trivial for PostgreSQL)
- Scale: 20 instances is sufficient for years

---

## Database Scaling

### Notification Requests Table

| Scale | Approach |
|-------|---------|
| < 10M rows/day | Single PostgreSQL instance with monthly partitions |
| 10M–100M rows/day | Read replica for analytics queries; write-only primary |
| > 100M rows/day | Horizontal sharding by user_id; 4–8 shards |

**Read replica usage:**
- Status API queries (`GET /notifications/{id}`) → primary (needs latest status)
- Analytics queries, batch campaign counts → read replica
- Preference Service reads → Redis cache (not DB)

### Delivery Attempts Table

At 100M rows/day (1,157 rows/sec inserts):
- PostgreSQL primary handles this comfortably with partitioned tables
- Monthly partition: ~3 billion rows (3TB at 1KB/row)
- Partition pruning + partial indexes keep query performance constant regardless of total table size
- Archive partitions > 90 days to S3 as Parquet → 100% partition drop, no data loss

---

## Caching Strategy for Scaling

### Preference Cache

| Approach | Detail |
|---------|--------|
| Key | `pref:{user_id}` |
| Value | Serialized preference map for all channels × categories |
| TTL | 1 hour |
| Eviction | Write-through on PATCH /preferences |
| Cache miss | Load from PostgreSQL, repopulate cache |
| Cache size | 100M users × 200B serialized = ~20 GB hot; cache only recent 10M active users (~2 GB) |

**Cache Stampede Prevention:**
- On cache miss, use `SET NX` with a lock key `pref_lock:{user_id}` (200ms TTL)
- First miss loads from DB; concurrent misses wait briefly then re-check cache
- Prevents 100 Fanout instances hammering DB for the same user simultaneously

### Template Cache

| Approach | Detail |
|---------|--------|
| Key | `tpl:{template_id}:{version}:{channel}:{locale}` |
| Value | Fully rendered template struct |
| TTL | 24 hours |
| Eviction | Explicit invalidation on template update |
| Cache size | ~1,000 active templates × 3 channels × 5KB avg = ~15 MB (fits in L2 of every instance) |

---

## Rate Limiting Architecture

### Producer Rate Limiting (API Layer)

```
Algorithm: Token Bucket (per producer service)
Storage:   Redis (key: ratelimit:{service_name}, value: tokens)
Refill:    10,000 tokens/minute
Burst:     Allow up to 2x in 1 second window
Response:  429 with Retry-After header
```

### Channel Rate Limiting (Dispatcher Layer)

Each dispatcher enforces provider-side rate limits using a Redis token bucket per provider API key:
```
key:   provider_limit:{provider}:{api_key}
value: available tokens
refill: per provider's stated limit (e.g., 600/sec for SendGrid)
```

When tokens are exhausted:
- Dispatcher NAKs the Kafka message (does not commit offset)
- Implements a brief delay (10ms) before re-polling
- This creates natural back-pressure without blocking the entire Kafka partition

---

## Backpressure Handling

The biggest risk is a slow Email Dispatcher (due to SendGrid throttling) causing `email.dispatch.requested` consumer lag to grow, eventually filling Kafka disk.

### Mitigation Strategies:

| Strategy | Mechanism |
|---------|----------|
| **Topic retention limit** | 3-day retention on dispatch topics; old messages expire (acceptable for batch campaigns, not transactional) |
| **Priority lanes** | Separate partitions (or separate topics) for CRITICAL/HIGH vs NORMAL/LOW. Dispatchers prioritize CRITICAL consumers |
| **Dynamic consumer scaling** | Kubernetes HPA scales dispatchers when consumer lag exceeds threshold (via KEDA: Kafka-based autoscaling) |
| **Circuit breaker on provider** | If provider returns >20% 5xx in 60 seconds, open circuit, stop consuming, alert ops, and route to fallback provider |

---

## Horizontal vs Vertical Scaling Matrix

| Component | Horizontal | Vertical | Notes |
|-----------|-----------|----------|-------|
| Notification API | Primary | Secondary | Stateless; add instances first |
| Fanout Service | Primary | No | Bottleneck is Kafka throughput, not memory |
| Email Dispatcher | Primary | No | Scale = number of API keys × providers |
| SMS Dispatcher | Primary | No | Limited by Twilio tier |
| Push Dispatcher | Primary | No | FCM batch API scales well horizontally |
| PostgreSQL | Read replicas | Yes (writes) | Write scale = partition + shard; read scale = replicas |
| Redis | Cluster sharding | No | Redis Cluster supports horizontal sharding |
| Kafka | Add brokers + partitions | No | Pure horizontal |
| ClickHouse | Replica + sharding | No | Columnar scales linearly |

---

## Load Testing Strategy

| Test Type | Target | Success Criteria |
|-----------|--------|----------------|
| Baseline throughput | 640 RPS sustained | < 30ms p99 API latency |
| Burst test | 50,000 RPS for 60 seconds | No message loss, queue drains within 5 min |
| Provider failure test | Kill SendGrid mock | Email DLQ fills, SMS/push unaffected |
| Campaign fanout test | 1M-user segment | Fanout completes in < 5 minutes |
| Consumer lag recovery | Pause dispatchers 5 min, then resume | Lag clears within 10 minutes |

---

## Interview Discussion Points

- How do you scale the Fanout Service without creating hot partitions for high-frequency users?
- What is KEDA and why is Kafka lag a better autoscaling signal than CPU for dispatcher pods?
- When would you introduce a priority queue mechanism to protect transactional OTP delivery during a campaign burst?
- What is the risk of using Redis token buckets for provider rate limiting when Redis itself becomes a bottleneck?
- How does `SELECT FOR UPDATE SKIP LOCKED` enable multi-instance Outbox processing without a distributed lock?
