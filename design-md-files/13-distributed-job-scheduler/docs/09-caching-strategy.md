# 09 — Caching Strategy: Distributed Job Scheduler

## Objective
Define what is cached, where, with what TTL, how it is invalidated, and what the failure mode is when the cache is unavailable — across all read-heavy paths in the distributed job scheduler.

---

## 1. Caching Architecture Overview

```mermaid
graph LR
    subgraph "Clients"
        API[API Layer]
        WK[Workers]
        SE[Scheduler Engine]
    end

    subgraph "Cache Layer (Redis)"
        JMC[Job Metadata Cache\nHash: job:{id}]
        WR[Worker Registry\nHash: workers:{type}]
        DL[Distributed Locks\nString: job-lock:{id}]
        RL[Rate Limiting\nString: ratelimit:{ns}:{endpoint}]
        IK[Idempotency Keys\nHash: idempotency:{ns}:{key}]
        NEC[Next Exec Time Cache\nZSet: jobs:next-exec:{ns}]
    end

    subgraph "Source of Truth"
        PG[(PostgreSQL)]
    end

    WK -->|cache-aside| JMC
    SE -->|cache-aside| JMC
    SE -->|read/write| DL
    SE -->|publish| WR
    WK -->|heartbeat write| WR
    API -->|check| RL
    API -->|read/write| IK
    JMC -.->|miss: read| PG
    WR -.->|miss: read| PG
```

---

## 2. Job Metadata Cache

### What is Cached
The full job definition: type, config, parameters, retry policy, execution config. This is what workers need to execute a job.

**Cache key:** `job:{job_id}`
**Data structure:** Redis Hash (field per job attribute for partial updates)
**TTL:** 5 minutes (300 seconds)
**Size estimate:** ~2KB per job × 1M cached jobs = 2GB (Redis heap) — manageable

### Cache Population Strategy: Cache-Aside (Lazy)

1. Worker receives `JobTriggered` event with `job_id`
2. Worker checks Redis: `HGETALL job:{job_id}`
3. **Cache hit:** use cached value
4. **Cache miss:**
   - Worker queries PostgreSQL read replica: `SELECT * FROM scheduling.jobs WHERE job_id = ?`
   - Stores result in Redis: `HMSET job:{job_id} ... EX 300`
   - Returns result

### Cache Invalidation

When a job is updated (PATCH /jobs/{id}), the API service:
1. Writes new state to PostgreSQL (in the same transaction as the version bump)
2. Publishes a `JobUpdated` domain event to Kafka (via outbox)
3. A dedicated `CacheInvalidationConsumer` (Kafka consumer group) receives the event and executes: `DEL job:{job_id}`

**Why publish invalidation via Kafka rather than direct Redis DEL?**
In a modular monolith, a direct `DEL` after commit is simpler and acceptable. In a microservices deployment (Phase 2), the worker service may be a separate process and cannot call Redis directly after the API service commits. The Kafka event ensures reliable cross-service invalidation.

**Stampede protection on cache miss:**
When a cache miss occurs for a hot job (e.g., a job that fires 1,000 times/minute), 1,000 concurrent workers might all try to read from PostgreSQL simultaneously. Mitigation:
- Use `SET job:{job_id} LOCKED EX 2 NX` before the DB read (mutex pattern)
- If lock not acquired, wait 50ms and retry the cache check (the other goroutine will have populated it)
- Fallback: if lock wait exceeds 500ms, read from DB directly (thundering herd in the worst case, bounded to once per 500ms)

---

## 3. Worker Registry Cache

### What is Cached
Live workers: worker ID, type, zone, capabilities, current load, last heartbeat.

**Cache key:** `workers:{type}:{zone}` → Redis Sorted Set scored by `last_heartbeat_at`
**Additional key:** `worker:{worker_id}` → Redis Hash (full worker details)
**TTL on worker entries:** None explicit — workers maintain their own TTL via heartbeat renewal

### Heartbeat Pattern

```
Worker lifecycle in Redis:
1. On start: HSET worker:{id} ... (full details)
                EXPIRE worker:{id} 30 (30-second TTL)
                ZADD workers:{type}:{zone} {timestamp} {worker_id}

2. Every 10 seconds: EXPIRE worker:{id} 30 (renew TTL)
                     ZADD workers:{type}:{zone} {now_timestamp} {worker_id}

3. On graceful shutdown: DEL worker:{id}
                          ZREM workers:{type}:{zone} {worker_id}

4. On crash (no shutdown): TTL expires after 30s
                            Stale detection: workers with score < now - 30s are removed by cleanup job
```

### Worker Discovery Query

Scheduler/Result Processor finding eligible workers:
1. `ZRANGEBYSCORE workers:HTTP:us-east-1a {now-30} +inf` — get workers with recent heartbeat
2. For each: `HGET worker:{id} currentLoad maxConcurrency` — check capacity
3. Return list of available workers sorted by utilization

**This query is read-only Redis; no PostgreSQL needed in the critical path.**

---

## 4. Distributed Locks

Fully documented in [07-scaling-strategy.md] and [11-failure-scenarios.md], but the caching aspect:

**Cache key:** `job-lock:{job_id}`
**Data structure:** String (value = scheduler_node_id + fencing_token)
**TTL:** = job timeout + buffer (e.g., `timeoutSeconds + 60`)
**Acquisition:** `SET job-lock:{job_id} {node_id}:{token} NX EX {ttl}`

**Critical invariant:** The Redis lock TTL must be longer than the maximum expected execution time. If TTL expires while the job is legitimately running, the scheduler may re-dispatch the job to another worker. The fencing token prevents stale result writes but does not prevent double execution.

**Design rule:** Set `lock_ttl = job_timeout_seconds + 30s`. If a job's timeout is 1 hour, lock TTL is 3,630 seconds.

---

## 5. Next Execution Time Cache (Scheduler Optimization)

For active-active scheduler mode (Phase 2), a scheduler node needs to quickly find which jobs are due next in its partition without full PostgreSQL scans every second.

**Cache key:** `jobs:next-exec:{partition_id}` → Redis Sorted Set
**Score:** Unix timestamp of `next_execution_time`
**Members:** `job_id` strings

### Population
When a job is registered or its `next_execution_time` is updated:
```
ZADD jobs:next-exec:{partition_id} {next_exec_timestamp} {job_id}
```

### Polling
Scheduler node queries its partition:
```
ZRANGEBYSCORE jobs:next-exec:{partition_id} 0 {now+5s_timestamp} LIMIT 0 1000
```

This replaces the PostgreSQL query for the look-ahead step. Only when a job is selected for dispatch does the scheduler fetch full details from PostgreSQL (to get job config and validate status).

**Cache coherence:**
- On job update/pause/delete: `ZREM jobs:next-exec:{partition_id} {job_id}`
- On misfire: re-add with new score
- Cache is rebuilt from PostgreSQL on scheduler node startup (full reconstruction from DB)
- TTL: no TTL on the sorted set (it's a persistent registry); items are managed explicitly

---

## 6. Rate Limiting Cache

**Cache key:** `ratelimit:{namespace}:{endpoint}:{window}`
**Data structure:** String (counter with atomic increment) or Sorted Set (sliding window)
**TTL:** = rate limit window (e.g., 60 seconds for per-minute limits)

### Sliding Window Rate Limiting

For more precise limiting (avoids boundary bursts with fixed windows):
```
ZADD ratelimit:{ns}:{ep} {now_ms} {request_id}
ZREMRANGEBYSCORE ratelimit:{ns}:{ep} 0 {now_ms - window_ms}
ZCARD ratelimit:{ns}:{ep}  → if > limit, reject
```

The Sorted Set scores are timestamps; old entries are pruned on each check. TTL set to window duration.

---

## 7. Idempotency Key Cache

**Cache key:** `idempotency:{namespace}:{idempotency_key}`
**Data structure:** Hash (status_code, response_body, created_at)
**TTL:** 24 hours
**Write:** On first request completion: `HMSET ... EX 86400`
**Read:** On every request: `HGETALL idempotency:{ns}:{key}`

**Fallback:** If Redis is unavailable, fall back to PostgreSQL `idempotency_keys` table (slower but durable). Redis stores a hot copy; PostgreSQL is the source of truth.

---

## 8. Cache Failure Modes and Fallbacks

| Cache Use Case | Redis Unavailable Behavior | Impact |
|---|---|---|
| Job metadata cache | Fall back to PostgreSQL read replica | 3-5x latency increase for workers; PostgreSQL load increases |
| Worker registry | Fall back to PostgreSQL `workers` table | Slower worker discovery; some routing decisions delayed |
| Distributed locks | Fall back to PostgreSQL advisory locks | Significant latency increase; potential lock contention |
| Rate limiting | Fail open (allow requests) OR fail closed (reject) | Configure per-namespace. Fail open is safer for most uses |
| Idempotency keys | Fall back to PostgreSQL idempotency table | Latency increase but no correctness loss |
| Next-exec cache | Fall back to PostgreSQL scheduler polling | Scheduler functions normally; slightly higher PostgreSQL load |

**Circuit breaker pattern for Redis:**
The application maintains a Redis circuit breaker (Spring Cloud Circuit Breaker / Resilience4j):
- Closed state: all Redis calls proceed normally
- Open state (triggered by >50% Redis error rate in 10s): all Redis calls short-circuit, fallbacks activate
- Half-open state: test Redis with probe calls every 30s
- This prevents cascading failure where every request waits for Redis timeout before falling back

---

## 9. Cache Sizing

| Cache Type | Entries | Entry Size | Total Redis RAM |
|---|---|---|---|
| Job metadata | 1M active jobs | 2KB | 2GB |
| Worker registry | 10,000 workers | 500B | 5MB |
| Distributed locks | 100,000 concurrent | 64B | 6.4MB |
| Rate limiting | 1,000 namespaces × 10 endpoints | 1KB (Sorted Set) | 10MB |
| Idempotency keys | 100k/day (24h window) | 512B | 50MB |
| Next-exec cache | 1M active jobs | 16B (score + key) | 16MB |
| **Total** | | | **~2.1GB** |

Redis cluster with 3 shards × 4GB RAM each (12GB total) provides comfortable headroom.

---

## 10. Cache Warming Strategy

**On system startup or cache flush:**
1. Load top 10,000 most-frequently-triggered jobs into metadata cache (proactive)
2. Rebuild worker registry from PostgreSQL (fast — only live workers)
3. Rebuild next-exec sorted sets for each partition (bulk ZADD)
4. Rate limiting: starts fresh (acceptable — rate limits will naturally catch up within one window)

**Proactive cache warming for burst events:**
If the system detects an upcoming cron burst (e.g., 100k jobs due at midnight), the scheduler pre-loads their metadata into Redis at 23:55, 5 minutes before the burst, to avoid cache-miss spikes during execution.

---

## Interview Discussion Points

**Q: How do you prevent cache stampedes when Redis goes down and comes back up?**
A: Use probabilistic early expiration (PER) — instead of expiring at exactly TTL, each request has a small probability of refreshing early (`prob = exp(-delta/beta)` where delta is remaining TTL and beta is a tuning constant). This distributes refreshes across time. Additionally, the LOCK-THEN-READ pattern (mutex on cache miss) limits concurrent DB reads for the same key.

**Q: What happens to job execution correctness if the job metadata cache returns stale data?**
A: Stale job metadata (old parameters, old timeout) affects correctness. The 5-minute TTL bounds staleness. For critical configuration changes (e.g., new authentication credentials), the `JobUpdated` event triggers immediate invalidation — not waiting for TTL. The risk window is zero for critical changes if cache invalidation is working.

**Q: Should you use Redis or Memcached for this cache?**
A: Redis. The system needs Sorted Sets (next-exec cache, rate limiting), Hashes (job metadata, worker registry), and atomic operations (SETNX for locks). Memcached only supports string keys/values — none of these data structures are available. Redis is the only correct choice here.
