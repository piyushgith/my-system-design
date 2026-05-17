# 09 — Caching Strategy: Pastebin / Code Sharing Platform

---

## Objective

Design a multi-layer caching architecture that maximizes cache hit ratio, minimizes latency, controls cost, and handles cache invalidation correctly. Identify what to cache, where, for how long, and how to invalidate safely.

---

## Why Caching Is Critical Here

Pastebin read traffic follows a **power law distribution (Pareto principle)**:
- Top 1% of pastes receive ~80% of total reads
- Most reads are for recently created, publicly shared pastes
- The same paste content never changes (immutable after creation)

These properties make Pastebin **one of the most cacheable systems possible**. A well-designed cache hierarchy can serve 95%+ of read traffic without hitting PostgreSQL or S3.

---

## Caching Architecture Overview

```
Request → [L1: CDN Edge] → [L2: Redis] → [L3: PostgreSQL / S3]
              ↑                 ↑               ↑
         ~5ms latency      ~1ms latency    ~30-300ms latency
         70-80% hit        85-90% hit       fallback only
         Public only       All pastes
```

---

## Layer 1: CDN (CloudFront / CloudFlare)

### What Is Cached

| Resource | Cache Behavior |
|----------|---------------|
| `GET /raw/{key}` (PUBLIC) | Cached at edge — maximum TTL |
| `GET /p/{key}` rendered page (PUBLIC) | Cached at edge |
| `/api/v1/meta/languages` | Cached 24 hours |
| `/api/v1/pastes/{key}` JSON (PUBLIC) | Cache with `s-maxage` |

### What Is NOT Cached

| Resource | Reason |
|----------|--------|
| PRIVATE pastes | Different content per authenticated user |
| UNLISTED pastes | Should not be indexed or publicly cached |
| POST/DELETE requests | Mutating — never cache |
| Authenticated API responses | User-specific data |

### Cache-Control Header Strategy

```http
# Public paste, expires in 1 hour
Cache-Control: public, max-age=3600, s-maxage=3600, stale-while-revalidate=60

# Public paste, expires in 1 day
Cache-Control: public, max-age=86400, s-maxage=86400

# Public paste, NEVER expires
Cache-Control: public, max-age=604800, s-maxage=2592000, immutable

# Private or unlisted paste
Cache-Control: private, no-store

# API metadata response (public paste)
Cache-Control: public, max-age=300, s-maxage=600
```

**`stale-while-revalidate`:** Allows CDN to serve slightly stale content while refreshing in background — eliminates tail latency spikes on cache refresh.

**`immutable`:** Tells CDN the content will never change (safe for NEVER-expiry pastes). CDN will not revalidate until TTL expires.

### CDN Cache Key Design

Default CDN cache key = URL + `Accept` + `Accept-Encoding` headers.

**Custom cache key (exclude irrelevant headers):**
- Include: URL path only (`/raw/{key}`)
- Exclude: `Cookie`, `Authorization`, `User-Agent`, `Referer`
- Why exclude Cookie: ensures all users get the same cached response for public pastes

**CloudFront cache policy:**
```json
{
  "HeadersConfig": { "HeaderBehavior": "none" },
  "QueryStringsConfig": { "QueryStringBehavior": "none" },
  "CookiesConfig": { "CookieBehavior": "none" }
}
```

### CDN Cache Invalidation

When a paste is deleted or expired:

```
1. Application publishes paste.deleted event to Kafka
2. CDN Invalidator consumer receives event
3. CDN Invalidator calls:
   CloudFront.createInvalidation({
     paths: ["/raw/abc123", "/p/abc123", "/api/v1/pastes/abc123"]
   })
4. Invalidation propagates globally in 5-30 seconds
```

**Cost consideration:** CloudFront charges $0.005 per 1,000 invalidation paths after the first 1,000/month free. At 6M deletions/month × 3 paths = 18M paths → ~$90/month. Acceptable.

**Alternative for cost reduction:** Use short TTLs (`max-age=300`) for pastes that might be deleted, and longer TTLs for NEVER-expiry pastes. Trade consistency for cost.

---

## Layer 2: Redis Application Cache

### Redis Architecture

```
Redis Cluster:
  - 3 shards (primary + replica each)
  - 6 nodes total
  - 10 GB memory total
  - Eviction policy: allkeys-lru (evict least recently used when memory full)
```

### What Is Cached in Redis

| Cache Key Pattern | Value | TTL | Eviction |
|------------------|-------|-----|---------|
| `paste:meta:{shortKey}` | JSON metadata | min(expiresAt-now, 3600s) | LRU |
| `paste:content:{shortKey}` | Raw text (< 1 KB only) | min(expiresAt-now, 3600s) | LRU |
| `paste:notfound:{shortKey}` | Sentinel ("1") | 60s | LRU |
| `rate:ip:{ip}:{window}` | Request count | Window TTL | LRU |
| `idempotency:{key}` | Response JSON | 86400s | LRU |
| `apikey:{hash}` | userId | 300s | LRU |

### Cache Value: Paste Metadata

```json
{
  "id": "paste_abc123",
  "shortKey": "abc123",
  "title": "Spring Config",
  "language": "yaml",
  "accessLevel": "PUBLIC",
  "contentType": "S3",
  "s3Key": "pastes/2026/05/abc123.txt",
  "contentSize": 4096,
  "expiresAt": "2026-05-18T12:00:00Z",
  "isPasswordProtected": false,
  "ownerId": "usr_xyz",
  "viewCount": 42
}
```

**Content caching rule:**
- Content inline in DB (< 1 KB): also cache content in Redis alongside metadata
- Content in S3 (≥ 1 KB): cache metadata only; S3 serves content (through CDN)

**Why not cache S3 content in Redis?**
- A 10 MB paste cached in Redis wastes 10 MB of expensive Redis memory
- S3 content is already cached at CDN for public pastes
- Redis memory should be reserved for metadata (hot path for access control checks)

### Negative Caching

```
Problem: Attacker requests /p/nonexistent1 /p/nonexistent2 ... /p/nonexistent9999
         Each request goes to DB → DB scanning thousands of non-existent keys

Solution: On 404:
  redis.set("paste:notfound:{key}", "1", EX, 60)

On next request:
  if redis.get("paste:notfound:{key}") exists → return 404 immediately
  (no DB hit)
```

**TTL 60 seconds:** Short enough that a legitimately created paste won't be "stuck as not found" for long. If a paste is created right after a negative cache entry, the 60-second window ensures it becomes accessible quickly.

---

## Cache Aside vs. Write-Through vs. Write-Behind

### Cache Aside (Chosen)

```
Read:
  1. Check Redis (cache)
  2. Cache hit → return
  3. Cache miss → read from DB/S3
  4. Write to cache
  5. Return to caller

Write:
  1. Write to DB (source of truth)
  2. Invalidate cache entry (DEL)
  3. Next read will populate cache (lazy warm)
```

**Why Cache Aside?**
- Simplest to implement correctly
- Cache only contains data that has been requested (no wasted memory)
- Application controls cache population strategy

**Why NOT Write-Through?**
- Would write every paste to Redis regardless of whether it gets read
- Memory waste for cold pastes (created once, never accessed again)
- Anonymous pastes that expire in 1 hour → written to Redis → evicted before first read

**Why NOT Write-Behind?**
- Risk of data loss if Redis fails before async write to DB
- Unnecessary complexity for Pastebin (DB writes are fast enough)

---

## Cache Invalidation Strategy

### On Paste Delete (User-Initiated)

```
1. DELETE /api/v1/pastes/{key}
2. App: UPDATE pastes SET is_deleted=TRUE WHERE short_key=?
3. App: redis.del("paste:meta:{key}", "paste:content:{key}")
4. App: (via Kafka) CDN invalidation for /raw/{key}
```

**Synchronous Redis invalidation** (not async) — critical path: deleted paste must not be served from Redis cache.

**Async CDN invalidation** — acceptable: CDN may serve stale content for up to 30 seconds after Redis is cleared.

### On Paste Expiry (System-Initiated)

Same as delete, triggered by cleanup job:
```
1. Cleanup job: UPDATE pastes SET is_deleted=TRUE
2. Cleanup job: redis.del("paste:meta:{key}", "paste:content:{key}")
3. Cleanup job: publish paste.deleted → CDN invalidation
```

### On View Count Update (Batch)

View count in cached metadata becomes stale after batch analytics update.

**Strategy: Accept stale view count in Redis.**
- View count cache is never actively updated
- Redis entry expires naturally (within its TTL: max 1 hour)
- On next cache miss + DB read: fresh view count is loaded
- This provides eventual consistency for view counts (acceptable)

---

## TTL Alignment Strategy

TTL in Redis must be **aligned with the paste's actual expiry** to prevent serving expired paste metadata from cache.

```java
long ttlSeconds = expiresAt == null
    ? 3600L  // NEVER expiry: still refresh every hour (in case of deletion)
    : Math.min(
        Duration.between(Instant.now(), expiresAt).getSeconds(),
        3600L  // Cap at 1 hour to limit stale serving
      );

redis.setex("paste:meta:{key}", ttlSeconds, metadataJson);
```

**Why cap at 3600 seconds even for longer-expiry pastes?**
- A paste could be manually deleted by its owner at any time
- Longer TTL = longer window of serving deleted paste from cache
- 1 hour is a reasonable balance between cache efficiency and freshness

---

## Hot Key Problem

**Scenario:** A paste is shared on Hacker News → 50,000 concurrent requests.

**What happens without mitigation:**
- All 50,000 requests hit Redis
- Redis single shard handles this easily (100,000+ ops/second)
- Problem: if Redis shard for that key goes down → all 50,000 fall through to DB

**Mitigations:**

1. **CDN First**: 90% of Hacker News traffic will hit the CDN (public paste). CDN absorbs the surge.

2. **Local in-process cache (L0)**: For extreme hot pastes, add a small in-process Caffeine cache (JVM heap):
   ```
   Caffeine cache: max 100 entries, TTL 30 seconds
   Used only for paste:meta:{key} if Redis hit rate exceeds 1,000 RPS for a single key
   ```
   Trade: 30 seconds of stale data if paste is deleted; accept for extreme cases.

3. **Redis key replication**: Manually replicate hot key across multiple Redis nodes under different key names, round-robin reads. Complex — avoid unless demonstrably needed.

---

## Cache Metrics and SLOs

| Metric | Target | Alert Threshold |
|--------|--------|----------------|
| Redis cache hit ratio | > 85% | < 70% |
| CDN cache hit ratio | > 75% | < 60% |
| Redis memory usage | < 80% | > 90% |
| Redis latency (p99) | < 5ms | > 20ms |
| Cache invalidation lag (Redis) | < 100ms | > 1s |
| Cache invalidation lag (CDN) | < 60s | > 300s |

---

## Compression in Cache

**Problem:** Raw paste content (even for < 1 KB entries) adds up in Redis memory.

**Solution:** Compress values before storing in Redis:
- Use LZ4 (fast) or Snappy (balanced) compression
- Compression ratio for code: 3-5x typical
- At 10 GB Redis for 1M cached pastes × average 5 KB → 50 GB without compression → 10-17 GB with 3-5x compression

**Implementation:** Transparent compression in Redis serialization layer — no application code change.

---

## Interview Discussion Points

- How do you decide what TTL to set for a cached paste that "never expires" but could be manually deleted?
- Why is Cache Aside preferred over Write-Through for a Pastebin use case?
- What is the thundering herd / cache stampede problem and how do you prevent it when Redis restarts?
- How does `stale-while-revalidate` help with tail latency at CDN level?
- If a paste is deleted by the user, but the CDN takes 30 seconds to invalidate, what does the user see? Is this acceptable?
- What is a hot key in Redis and how does Pastebin's CDN layer help mitigate it naturally?
- Should you cache negative results (not found)? What are the risks?
