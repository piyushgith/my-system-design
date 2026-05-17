# 09 — Caching Strategy: URL Shortener

---

## Objective

Define a multi-layered caching strategy that achieves sub-10ms redirect latency for cache hits, handles the hot URL problem, and maintains cache consistency with the source of truth.

---

## Caching Goals

| Goal | Target |
|---|---|
| Redirect p50 latency | < 5ms (cache hit) |
| Redirect p99 latency | < 50ms (cache miss falls through to DB) |
| Cache hit rate | > 95% for active redirect traffic |
| Cache memory efficiency | Serve 80% of traffic from 6GB Redis memory |
| Consistency | Stale data tolerated < 60s (except deletes, which are immediate) |

---

## Cache Layers

```
Browser Request
    ↓
L1: CDN Edge Cache (CloudFront)        ← 301 redirects; global; near-zero latency
    ↓ (miss or 302)
L2: Application Process Cache (Caffeine) ← top ~10K hot URLs per pod; 60s TTL
    ↓ (miss)
L3: Redis Distributed Cache             ← all active URLs; 24h TTL
    ↓ (miss)
L4: PostgreSQL Read Replica             ← source of truth; 10-20ms
```

---

## Layer 1: CDN Caching

**What's cached**: Only `301 Moved Permanently` responses for explicitly flagged permanent URLs.

**When to use 301 (CDN-cached)**:
- User explicitly selects "permanent / never change" mode
- Enterprise customers with verified domains
- After URL has been active for > 30 days without modification

**Cache-Control headers**:
```http
# For CDN-cacheable (permanent) URLs:
Cache-Control: public, max-age=86400, stale-while-revalidate=3600
Surrogate-Key: url-aB3xYz                   ← CloudFront tag for targeted purge

# For all other redirects (302):
Cache-Control: no-store, no-cache
Pragma: no-cache
```

**Cache invalidation**:
- URL deletion → CloudFront cache invalidation API call for `url-aB3xYz` surrogate key
- Invalidation completes in < 60 seconds globally
- Alert if invalidation fails (retry + operator alert)

**CDN hit rate target**: 60–70% of total redirects (only permanent URLs qualify for CDN caching)

---

## Layer 2: Application-Level Cache (Caffeine)

**Why add an in-process cache on top of Redis?**

Redis, while fast (< 1ms), still requires a network round-trip. At 10K RPS per pod, even a 1ms network call adds up. An in-process Caffeine cache eliminates the network hop entirely.

**Configuration**:
```
Maximum entries: 10,000 URLs per pod instance
TTL after write: 60 seconds
Eviction policy: TinyLFU (frequency + recency — better than pure LRU for skewed access)
```

**Memory footprint**:
- 10,000 entries × 250 bytes ≈ 2.5 MB per pod instance — negligible

**Hot URL benefit**: If `viral-link` gets 50K RPS hitting 10 pods, each pod's local cache handles 5K RPS without touching Redis. Redis effectively sees 10 requests per 60-second window instead of 50K × 60 = 3M requests.

**Cache invalidation challenge**: In-process cache cannot be invalidated remotely. On URL deletion:
1. Redis key is evicted immediately (synchronous on delete API call)
2. Caffeine entries expire within 60 seconds (stale window)
3. Mitigation: Caffeine cache includes the URL `status` field; check `status != DELETED` on every cache hit

---

## Layer 3: Redis Distributed Cache

**Primary cache layer** for the redirect hot path.

### Key Design

```
# URL redirect cache
KEY:   "url:{shortCode}"
VALUE: JSON or MessagePack encoded:
       { longUrl, status, expiresAt, geoRules: [...], version }
TTL:   86400 seconds (24 hours)

# Click counter (for click-limit expiration)
KEY:   "clicks:{shortCode}"
VALUE: Integer (atomic counter)
TTL:   No TTL (permanent — expires when URL expires or deleted)

# Rate limit counter
KEY:   "rl:{userId}:{minute}"
VALUE: Integer (request count)
TTL:   60 seconds

# Idempotency cache
KEY:   "idem:{idempotencyKey}"
VALUE: Serialized HTTP response
TTL:   86400 seconds (24 hours)
```

### Cache Population Strategy

**Cache-Aside (Lazy loading)**:
1. Check Redis on redirect
2. If miss → read from Postgres read replica
3. Set in Redis with TTL
4. Serve redirect

**Cache Warm-up (Proactive)**:
- On `UrlCreated` event: immediately SET in Redis (avoids cold start miss on first redirect)
- On system startup: pre-warm top 100K URLs from DB into Redis

### Serialization Format

| Format | Size | Speed | Decision |
|---|---|---|---|
| JSON | ~250 bytes | Fast to debug | Development |
| MessagePack | ~120 bytes | 2x faster, smaller | Production |
| Protobuf | ~100 bytes | Fastest, schema-tied | Over-engineered for this use case |

**Production recommendation**: MessagePack — good balance of compactness and simplicity without schema management overhead.

---

## Cache Invalidation Strategy

**The hardest problem in caching.** URL shortener has well-defined invalidation events:

| Event | Cache Action | Latency |
|---|---|---|
| URL deleted | Redis DEL `url:{code}` — synchronous in delete API handler | Immediate (< 1ms) |
| URL expired (TTL reached) | Redis key TTL fires naturally | Within 1s of expiry |
| URL expired (click limit) | Atomic check: `INCR clicks:{code}` → if >= maxClicks → DEL `url:{code}` | Immediate |
| URL geo rules updated | Redis DEL `url:{code}` then re-SET with new value | Immediate |
| URL blocked (safety scan) | Redis SET `url:{code}.status = BLOCKED` + CDN purge | < 60s |

**Consistency guarantee**: URL deletion is the only hard consistency requirement. All others are eventually consistent (< 60s stale window).

---

## Cache Miss Handling

### Cold Start (New URL)

```
1. UrlCreated event received by CacheWarmer consumer
2. Immediately SET url:{shortCode} in Redis
3. First redirect request hits Redis (warm)
```

### Cache Miss Under Load

```
1. Redis miss → query PostgreSQL read replica
2. Multiple concurrent requests for same code (cache stampede risk)

Mitigation: Redis probabilistic early expiration
- Instead of exact TTL, randomly expire entries slightly before TTL
- Prevents all requests hitting DB at exactly the same time
```

**Cache stampede prevention** (for high-traffic URLs):
```
Implementation options:
1. Mutex/lock: First miss acquires Redis lock, fetches from DB, warms cache. Others wait.
   - Risk: Lock contention under high load
2. Background refresh: Return stale value while asynchronously refreshing
   - Better UX; Caffeine supports this natively (refreshAfterWrite)
3. Probabilistic early expiration: XFetch algorithm
   - No coordination needed; gradually increases cache refresh probability as TTL approaches
```

---

## Click Counter Caching

**Problem**: Click-limit expiration requires knowing total click count. Querying ClickHouse (analytics DB) on every redirect is too slow.

**Solution**: Redis counter

```
On each redirect:
1. INCR clicks:{shortCode}                 ← atomic, O(1)
2. If result >= maxClicks:
   - SET url:{shortCode} = {status: EXPIRED}  ← update cached entry
   - DELETE short_urls.status = EXPIRED in Postgres (async)

Persistence:
- Redis INCR values are periodically flushed to PostgreSQL url_click_counts table
- Flush frequency: every 60 seconds via scheduled job
- Redis acts as write buffer; Postgres is the durable store
```

**Failure scenario**: Redis crashes → click counter lost. Mitigation:
- Redis AOF persistence (append-only log) — recovers data up to last flush
- Accept minor discrepancy in click count (analytics tolerance)
- Critical for click-limit expiration: set 10% buffer (expire at 90% of maxClicks, verify against DB before final expiry)

---

## Geo-Routing Cache

**Problem**: GeoRules is a JSONB field (list of country→URL mappings). Parsing on every redirect adds latency.

**Solution**: Pre-serialize resolved geo rules into Redis:
```
KEY: "url:{shortCode}"
VALUE: {
  longUrl: "...",         ← default target
  geoRules: {             ← map keyed by country code
    "IN": "https://example.in/page",
    "US": "https://example.com/page"
  },
  status: "ACTIVE",
  expiresAt: 1705318260
}
```

GeoIP lookup (IP → Country) is performed in-application using MaxMind GeoLite2 database (local binary, no network call, < 0.1ms lookup).

---

## Analytics Cache (Click Stats)

**Problem**: Analytics dashboard loads click stats for a URL — these are expensive ClickHouse aggregations.

**Solution**: Cache aggregated results in Redis

```
KEY:   "analytics:{shortCode}:{dateRange}:{granularity}"
VALUE: Pre-computed JSON analytics response
TTL:   5 minutes (acceptable staleness for dashboard)

Cache warming: When user opens dashboard, async trigger pre-computation of 7-day, 30-day views
Cache busting: Not needed (staleness is acceptable); TTL is the eviction mechanism
```

---

## Cache Metrics

| Metric | Threshold | Alert |
|---|---|---|
| Redis hit rate | < 90% | Warning |
| Redis memory usage | > 80% of max | Scale Redis |
| Caffeine hit rate (per pod) | < 70% for hot traffic | Review local cache size |
| Cache eviction rate | Spiking | Memory pressure — increase Redis RAM |
| Redis replication lag | > 100ms | Replica sync issue |

---

## Tradeoffs

| Decision | Tradeoff |
|---|---|
| 60s Caffeine TTL | Stale redirects up to 60s after URL deletion — acceptable for most use cases |
| Cache-aside (not write-through) | Possible cold cache on first redirect — mitigated by cache warmer on `UrlCreated` event |
| MessagePack over JSON | Faster + smaller but not human-readable for debugging; add debug endpoint to inspect cache in JSON |
| No database write on redirect | All click tracking async — if Kafka is down, click events are lost (not click counts, which are in Redis) |
| Redis as click counter | Redis crash loses recent counts — AOF mitigates but doesn't eliminate this risk |

---

## Interview Discussion Points

- **How do you handle cache stampede for a suddenly viral URL?** Use background refresh with stale-while-revalidate: return the cached (possibly stale) value while asynchronously fetching the fresh one. Caffeine's `refreshAfterWrite` implements this natively
- **What's your eviction strategy if Redis runs out of memory?** `allkeys-lru` — Redis evicts the least-recently-used URL from cache. The next redirect for that URL causes a cache miss and refills from DB. No data loss — just a latency spike
- **How do you test cache correctness?** Integration tests that: (1) create URL, (2) verify Redis key set, (3) delete URL, (4) verify Redis key evicted, (5) verify redirect returns 404. Use Testcontainers for Redis in tests
- **Would you cache the analytics query results?** Yes, but with a very short TTL (5 minutes) and clearly document the staleness to users ("stats updated every 5 minutes"). Real-time analytics would require no caching, which contradicts ClickHouse query latency expectations
