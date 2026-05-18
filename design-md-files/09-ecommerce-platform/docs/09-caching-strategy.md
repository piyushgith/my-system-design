# 09 — Caching Strategy: E-Commerce Platform

---

## Objective

Define multi-layer caching across CDN, application, and database tiers to handle e-commerce's read-heavy traffic patterns — catalog browsing, search, inventory checks, and cart operations — while maintaining consistency on critical transactional data.

---

## Cache Layers

```
Browser Cache
    ↓
CDN (CloudFront/Akamai)
    ↓
API Gateway Cache (optional, short TTL)
    ↓
Application Cache (Redis Cluster)
    ↓
Database Read Replica
    ↓
Primary Postgres
```

---

## What to Cache vs What Not to Cache

| Data | Cache? | Reason |
|---|---|---|
| Product images | Yes — CDN | Static, immutable after upload |
| Product catalog/details | Yes — CDN + Redis | Read-heavy, low update frequency |
| Category listings | Yes — CDN | Changes only on product add/remove |
| Search results | Yes — Redis short TTL | Expensive ES query, acceptable staleness |
| Inventory count (display) | Yes — Redis | Near-real-time OK for display |
| Inventory count (purchase) | Redis atomic only | Must be accurate, use DECR |
| Cart | Yes — Redis | Session data, not DB |
| Order history list | Yes — Redis short TTL | User-specific, expires on new order |
| Order detail | No | Must always be fresh |
| Payment status | No | Must always be fresh |
| User profile | Yes — Redis | Low mutation rate |
| Flash sale eligibility | Yes — Redis | Per-user flag, fast lookup |

---

## Layer 1: CDN Caching

### Product Images

- Upload to S3, served via CloudFront
- Cache-Control: `public, max-age=31536000, immutable` (1 year — never changes after upload)
- URL contains content hash: `/images/product-abc123-v2.jpg`
- New upload = new URL = no cache invalidation needed

### Product Detail Pages (SSR or API Response)

- Cache-Control: `public, max-age=300` (5-minute TTL)
- Vary: Accept-Encoding, Accept-Language
- Surrogate-Key: `product:{productId}` (for targeted purge)
- On product update → CDN API purge by surrogate key

### Category/Listing Pages

- Cache-Control: `public, max-age=60` (1-minute TTL)
- Acceptable: user sees slightly stale listing
- Purge on: product added/removed from category

### What NOT to CDN Cache

- Cart, checkout, order confirmation (user-specific)
- Anything with `Authorization` header (CDN won't cache by default)
- Payment APIs

---

## Layer 2: Redis Application Cache

### Cache Design Patterns Used

**Cache-Aside (Lazy Loading):**
- App checks Redis first
- On miss: query DB, store in Redis with TTL, return
- Used for: product details, user profile, order history

**Write-Through:**
- App writes to DB and Redis simultaneously
- Used for: inventory updates (always want Redis current)

**Write-Behind (Async):**
- App writes to Redis immediately, async job writes to DB
- Used for: cart (low risk of data loss, high frequency updates)

### Key Schema

```
product:detail:{productId}          TTL: 300s
product:inventory:{productId}       TTL: 60s (refresh frequently)
product:search:popular              TTL: 600s
category:{categoryId}:page:{n}      TTL: 120s
cart:{userId}                       TTL: 86400s (24h, reset on activity)
order:history:{userId}:page:{n}     TTL: 300s
user:profile:{userId}               TTL: 3600s
flash:eligible:{userId}:{saleId}    TTL: 3600s
rate:limit:{userId}:{action}        TTL: 60s (sliding window)
```

### Inventory Caching — Special Case

Inventory has two use cases with different consistency requirements:

**Display inventory** (product page "X left in stock"):
- Redis counter, TTL 60s
- Cache-aside: load from DB on miss
- Acceptable to show ±5 units stale

**Purchase inventory** (actual buy):
- Redis DECR (atomic decrement)
- Lua script: `IF inventory > 0 THEN DECR RETURN 1 ELSE RETURN 0`
- This IS the source of truth for purchases; Postgres is reconciled async
- Periodic sync job: compare Redis counter vs Postgres, alert on drift

### Cart Caching

Cart is pure Redis — no Postgres write until checkout:

```
HSET cart:{userId} {productId} {quantity}
EXPIRE cart:{userId} 86400
```

On checkout:
1. Read cart from Redis
2. Validate prices (fetch from DB/cache — user may have stale cart)
3. Check inventory atomically in Redis
4. Create order in Postgres
5. Delete cart from Redis

Guest cart → saved with session ID, merged on login.

---

## Cache Invalidation Strategy

### Time-Based (TTL)

Default strategy. Simple, predictable. Acceptable staleness per entity:

| Entity | TTL | Max Staleness Acceptable |
|---|---|---|
| Product detail | 300s | 5 minutes |
| Inventory display | 60s | 1 minute |
| Category page | 120s | 2 minutes |
| Search results | 180s | 3 minutes |
| User profile | 3600s | 1 hour |

### Event-Based Invalidation

For entities where staleness causes real harm:

- Product price change → invalidate `product:detail:{productId}` + CDN purge
- Product goes out of stock → invalidate display inventory + CDN purge
- Order placed → invalidate `order:history:{userId}:*`

Implementation: Kafka event `ProductUpdated` → cache invalidation consumer → Redis DEL + CDN API purge

### Cache Stampede Prevention

When popular cache key expires, 1000 concurrent requests hit DB:

**Solutions:**
1. **Probabilistic early expiration**: Re-compute cache slightly before TTL expires based on probability
2. **Mutex lock on cache miss**: First request gets lock, others wait and read stale/locked result
3. **Background refresh**: Async job refreshes cache before expiry for hot keys

For e-commerce: top 1000 most-viewed products → background refresh job every 30s (never cold cache for hot items).

---

## Redis Cluster Configuration

```
3 master nodes + 3 replica nodes
Keyspace: 16,384 hash slots distributed across 3 masters
Automatic failover: replica promoted on master failure (< 30s)
```

### Memory Sizing

| Cache Type | Estimated Size |
|---|---|
| 10M product details (2KB avg) | ~20 GB |
| 5M active carts (500B avg) | ~2.5 GB |
| 1M user profiles (1KB avg) | ~1 GB |
| Rate limit keys | ~500 MB |
| Inventory counters | ~500 MB |
| **Total** | **~25 GB** |

3 nodes × 16 GB = 48 GB available. Comfortable headroom.

### Eviction Policy

`allkeys-lru` — when memory full, evict least recently used keys across all keyspaces.

Not `volatile-lru` — some critical keys (inventory counters) have no TTL; must not be silently evicted.

---

## Elasticsearch Query Cache

- ES node query cache: frequently executed queries cached in JVM heap
- Hot product searches cached automatically
- Index-level `request_cache`: for aggregations and constant-score queries
- Monitor cache hit rate in ES metrics

---

## Cache Warming

On deployment / Redis restart:

1. Pre-load top 1000 products by view count
2. Pre-load active flash sale inventory counters
3. Pre-load top categories
4. Pre-load popular search queries

Warming job runs before traffic shifts to new deployment (blue-green deployment).

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Cart in Redis only | Fast, no DB write per item add | Cart lost on Redis failure (mitigate with persistence) |
| Inventory in Redis | Prevent oversell with atomics | Redis is not primary DB; need reconciliation job |
| Cache-aside for catalog | Simple, familiar | Cache stampede on cold start |
| Long CDN TTL for images | Near-zero bandwidth cost | Must use content-addressed URLs |
| Event-driven invalidation | Accurate cache | Kafka lag means brief inconsistency window |

---

## Risks

- **Redis single point of failure**: Mitigate with Redis Cluster + AOF persistence
- **Cache poisoning**: Validate all data before caching; never cache user-supplied raw data
- **Stale inventory display**: 60s TTL acceptable; real protection is at purchase time via atomic decrement
- **Memory exhaustion**: Monitor RSS, set maxmemory with LRU eviction

---

## Interview Discussion Points

- **"How do you handle cache invalidation for product prices?"** → Event-driven: price change event → invalidate product cache + CDN purge
- **"How do you prevent overselling with Redis?"** → Lua script atomic check-and-decrement; explain why DECR alone isn't enough
- **"What's your Redis memory estimate?"** → Walk through per-entity size × expected keys
- **"What happens if Redis goes down?"** → Cart lost (acceptable, session data); inventory falls back to DB; latency spikes but system stays up
- **"How do you handle cache stampede?"** → Background refresh for hot keys, mutex lock for cold cache miss
