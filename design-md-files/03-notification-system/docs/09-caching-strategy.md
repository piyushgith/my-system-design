# 09 — Caching Strategy: Notification System

---

## Objective

Define all caching layers in the Notification System, the data cached at each layer, eviction strategies, TTL choices, and cache coherence approaches. Caching is not optional here — it is on the critical delivery path.

---

## Why Caching Is Critical Here

The Fanout Service dispatches notifications at 50,000/sec at peak. For every single notification, it must:
1. Look up user notification preferences (channel + category settings)
2. Look up the template (for pre-validation before dispatch job is created)

Without caching:
- Preference lookup: 100M users × 1 DB read per notification = 50,000 DB reads/sec at peak
- Template lookup: 50,000 DB reads/sec at peak

A single PostgreSQL instance handles ~10,000–30,000 simple reads/sec. The DB would be crushed in seconds. **Caching is existentially required.**

---

## Cache Architecture Overview

```mermaid
graph LR
    subgraph Fanout Service
        L1[In-Process Cache\nCaffeine/Guava\n10 sec TTL]
    end

    subgraph Redis Cluster
        RC[Redis Cluster\n3 primary + 3 replica nodes]
    end

    subgraph PostgreSQL
        PG[(Primary + Read Replica)]
    end

    Fanout Service -->|Cache miss| Redis Cluster
    Redis Cluster -->|Cache miss| PostgreSQL
    Fanout Service -->|L1 hit| Fanout Service
```

### Two-Tier Cache for Preferences

**Tier 1: In-process (Caffeine)** — inside each Fanout pod
- Key: `pref:{user_id}`
- TTL: 10 seconds
- Max size: 100,000 entries per pod (100K × 200B = ~20 MB)
- Serves the most recently seen users without any network I/O
- Best for high-frequency users (power users who trigger many notifications)

**Tier 2: Redis Cluster** — shared across all Fanout pods
- Key: `pref:{user_id}`
- TTL: 1 hour
- Max size: 2 GB (10M active users × 200B)
- Hit rate target: > 95% (most users have stable preferences)

**Tier 3: PostgreSQL read replica** — authoritative fallback
- Only hit on Redis miss
- Loads full preference set, writes to Redis (write-through), then to Caffeine

---

## Caching Data by Entity

### User Notification Preferences

| Attribute | Value |
|-----------|-------|
| **Cache key** | `pref:{user_id}` |
| **Value format** | Serialized map: `{EMAIL:MARKETING: true, SMS:TRANSACTIONAL: true, ...}` + quiet_hours |
| **Serialization** | MessagePack (compact binary, ~40% smaller than JSON) |
| **L1 TTL** | 10 seconds |
| **Redis TTL** | 1 hour |
| **Eviction policy** | LRU (Redis `allkeys-lru`) |
| **Write strategy** | Write-through: PATCH preference → update DB → update Redis → Caffeine auto-expires in 10 sec |
| **Cache stampede** | `SET NX pref_lock:{user_id}` with 200ms TTL during DB load |

**Why 1-hour TTL?**
User preference changes are rare (weekly at most). A 1-hour stale window means a user who opts out of Marketing emails may still receive one notification within the hour. This is operationally acceptable and the tradeoff is explicitly documented for compliance review.

**Exception:** When a user hard-unsubscribes (bounce event), preference is updated synchronously and Redis key is explicitly deleted (TTL invalidation), not just written over.

---

### Templates

| Attribute | Value |
|-----------|-------|
| **Cache key** | `tpl:{template_id}:{version}:{channel}:{locale}` |
| **Value format** | Serialized Template struct (subject, body_html, body_text, variables_schema) |
| **Serialization** | JSON (readability) or MessagePack (performance) |
| **L1 TTL** | 24 hours (templates are immutable by version) |
| **Redis TTL** | 24 hours |
| **Eviction policy** | LRU |
| **Write strategy** | Write-on-create: when a new template version is created, pre-warm the cache |
| **Invalidation** | Explicit DELETE on `tpl:{id}:{version}:*` when a version is deprecated |

**Why long TTL for templates?**
Template versions are immutable once published. Version 3 of `order-confirmed` will never change its content. A 24-hour TTL is safe. The only invalidation trigger is marking a version as deprecated (rare, operator action).

---

### Idempotency Keys

| Attribute | Value |
|-----------|-------|
| **Cache key** | `idempotency:{key}` |
| **Value format** | JSON response payload (notification_id, status) |
| **Storage** | Redis only (no DB backing needed) |
| **TTL** | 24 hours |
| **Eviction** | TTL-based (no LRU — all keys are important while alive) |
| **Write strategy** | `SET NX` with TTL on first call; `GET` on subsequent calls |

**Lock vs stored response pattern:**
- First call: `SET NX idempotency:{key} "PROCESSING" EX 30` — returns PROCESSING to concurrent duplicates
- After completion: `SET idempotency:{key} {response_json} EX 86400` — stores full response for 24h
- This pattern handles the case where the original request is still processing when a duplicate arrives

---

### Provider Rate Limit Tokens

| Attribute | Value |
|-----------|-------|
| **Cache key** | `provider_tokens:{provider}:{api_key_id}` |
| **Value format** | Integer (available tokens) |
| **TTL** | Rolling (refilled per second) |
| **Algorithm** | Token Bucket: `DECRBY` on consume; background refill via INCRBY |
| **Redis command** | Lua script for atomic check-and-decrement |

**Why Lua script?**
A check-and-decrement operation must be atomic. `GET` then `DECRBY` is a TOCTOU race condition. A Lua script executes atomically on Redis.

---

### Rate Limit Counters (API Layer)

| Attribute | Value |
|-----------|-------|
| **Cache key** | `ratelimit:{producer_service}:{window_start}` |
| **Value format** | Integer (request count in window) |
| **Algorithm** | Sliding window counter |
| **TTL** | Window size (60 seconds) |
| **Storage** | Redis only |

---

### Notification Status (Short-Term)

| Attribute | Value |
|-----------|-------|
| **Cache key** | `notif_status:{notification_id}` |
| **Value format** | Current status + delivery attempts summary |
| **TTL** | 5 minutes |
| **Purpose** | Status API requests that arrive shortly after submission; avoids immediate DB read |
| **Write strategy** | Written by Notification API on submission; updated by Delivery Log Service on status change |

---

### In-App Unread Count

| Attribute | Value |
|-----------|-------|
| **Cache key** | `inbox_unread:{user_id}` |
| **Value format** | Integer |
| **TTL** | 5 minutes |
| **Purpose** | Inbox API endpoint `GET /inbox` returns unread count; cached to avoid COUNT(*) on large inbox tables |
| **Invalidation** | Incremented when in-app notification created; decremented on read |

---

## Cache Coherence Challenges

### Problem 1: Preference Update Propagation

**Scenario:** User opts out of Marketing email at 14:00. A campaign email is dispatched at 14:01 from a Fanout pod with a valid L1 cache.

**Risk:** One email delivered within 10-second L1 window after opt-out.

**Mitigation:**
- L1 TTL of 10 seconds limits exposure window to 10 seconds max
- For Marketing opt-outs, this is legally acceptable (CAN-SPAM allows up to 10 business days)
- For GDPR erasure requests: immediately delete Redis key AND broadcast a cache invalidation event via Kafka `user.preference.updated` topic to all Fanout pods to proactively evict L1

**Why not synchronous L1 invalidation on every preference change?**
Broadcasting invalidation to 25+ Fanout pods requires either shared state or a pub/sub mechanism. Kafka-based invalidation adds ~100ms latency. L1 TTL expiry is simpler and equally effective for non-critical cases.

### Problem 2: Template Version Pinning

**Scenario:** Notification was submitted with `template_version: 3`. Version 4 is created and version 3 is deprecated. The cache still holds version 3 for 24 hours.

**This is intentional and correct behavior.** The notification_request pinned version 3 at submission. Dispatchers cache version 3 and should use it regardless of newer versions existing.

### Problem 3: Redis Cluster Node Failure

**Scenario:** One Redis primary node fails. Its slots are served by a replica until promotion completes (~10–30 seconds).

**Risk:** Cache misses during failover → DB load spike

**Mitigation:**
- Read from Redis replica during primary failover (Redis Cluster serves reads from replicas on failure)
- Application-level retry: if Redis returns CLUSTERDOWN error, wait 500ms and retry once, then fall back to DB
- DB read replica can absorb 2–5x normal load for brief periods (sized with 3x headroom)

---

## Cache Sizing

| Cache Layer | Estimated Size | Redis Nodes |
|-------------|---------------|-------------|
| User preferences (10M active) | 2 GB | Shared cluster |
| Templates (1,000 versions × 3 channels) | 15 MB | Shared cluster |
| Idempotency keys (55M/day at peak, 24h TTL) | 5 GB | Shared cluster |
| Rate limit counters | 100 MB | Shared cluster |
| Provider tokens | 10 MB | Shared cluster |
| In-app unread counts (20M DAU) | 160 MB | Shared cluster |
| **Total** | **~8 GB** | 3× 16 GB nodes = 48 GB raw (6x headroom) |

---

## Eviction Policy

Redis configured with `maxmemory-policy: allkeys-lru`:
- When memory pressure is reached, evict the least recently used key across all datasets
- This is safe for the Notification System because all cached data is backed by PostgreSQL
- A cache miss is a latency cost, not a correctness error

**Exception:** Idempotency keys should NOT be LRU-evicted (losing them causes duplicate delivery). Solution: Idempotency keys stored in a dedicated Redis instance with `noeviction` policy + alert on memory > 80%.

---

## Interview Discussion Points

- Why is a two-tier cache (L1 in-process + L2 Redis) better than just Redis for the Fanout Service at 50K RPS?
- What happens to the idempotency guarantee if Redis becomes unavailable and the Notification API falls back to DB-only checks?
- How do you ensure that a preference opt-out is respected within 1 second, not 10 seconds or 1 hour?
- Why is `allkeys-lru` safe for Redis in this system but NOT safe for the idempotency key store?
- When would you consider using a read-through cache library (like Spring Cache) versus a manual Redis integration?
