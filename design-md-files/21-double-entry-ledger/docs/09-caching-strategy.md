# 09 — Caching Strategy: Double-Entry Ledger Service

---

## Objective

Define the caching layers for the double-entry ledger, covering balance caching, idempotency key caching, account metadata caching, and the consistency model between cache and authoritative source (PostgreSQL).

---

## Caching Challenges Specific to a Ledger

A ledger has unique caching constraints:

1. **Correctness over performance**: An incorrect cached balance used for a payment authorization can allow overspending — financial loss
2. **High write invalidation rate**: Every posting invalidates balances for all affected accounts
3. **Hot accounts**: The platform float account receives thousands of writes per second — cache invalidation must be near-instant
4. **Idempotency must survive cache restarts**: Redis restart cannot allow duplicate postings

---

## Cache Layers

| Layer | Technology | What is Cached | TTL | Invalidation |
|---|---|---|---|---|
| Balance cache | Redis Cluster | Account balance + snapshot version | 5 seconds (hard TTL) | Explicit DEL on every posting |
| Idempotency cache | Redis Cluster | idempotency_key → posting_id | 24 hours | TTL expiry |
| Account metadata | Redis Cluster | Account type, status, currency | 60 seconds | Explicit DEL on account status change |
| Idempotency backup | PostgreSQL `idempotency_cache` table | Same as above | 7 days | Batch cleanup job |

---

## Balance Cache Design

### Cache Key Structure

```
balance:{account_id}:{currency}
```

Multi-currency accounts have separate cache keys per currency (most accounts are single-currency).

### Cache Value Structure

```json
{
  "balance": 1250000,
  "currency": "INR",
  "snapshot_version": 42,
  "snapshot_as_of": "2024-01-15T10:30:00.123Z",
  "cached_at": "2024-01-15T10:30:01Z"
}
```

### Cache Population Strategy: Read-Through with Short TTL

```
Read path:
  1. GET balance:{account_id} from Redis
  2. If HIT: return cached value
  3. If MISS: SELECT from account_snapshots (PostgreSQL)
  4. SET balance:{account_id} TTL=5s in Redis
  5. Return value

Write path (on posting commit):
  1. Commit journal_entries + snapshot update to PostgreSQL
  2. DEL balance:{account_id} for each affected account from Redis
  (Cache repopulation happens lazily on next read)
```

**Why invalidate (DEL) vs write-through (SET new value)?**

Write-through would require the exact new balance to be computed and written to Redis in the same operation as the DB commit. However:
- The CAS snapshot update may fail and retry — we don't want to write a wrong value to Redis
- The snapshot update and Redis write cannot be atomic — a crash between them leaves Redis with a wrong value
- DEL is safe: it causes a cache miss on next read, which reads the authoritative value from DB

**5-second hard TTL**: Even if the DEL is lost (Redis unavailable), the balance becomes stale for at most 5 seconds. This is the maximum acceptable stale window for payment authorization. After 5 seconds, the expired key is evicted and a fresh DB read occurs.

---

## Idempotency Cache Design

The idempotency cache is the first line of defense against duplicate postings on retry.

### Cache Key: `idempotency:{idempotency_key}`

### Cache Value: `posting_id` (UUID string)

### Flow:

```
1. Check Redis: GET idempotency:{key}
   - HIT → return existing posting_id (skip DB)
   - MISS → check DB: SELECT FROM postings WHERE idempotency_key=?
     - Found in DB → populate Redis, return posting_id
     - Not found → proceed with new posting

2. After successful posting commit:
   - SET idempotency:{key} = posting_id TTL=24h

3. Idempotency backup (PostgreSQL idempotency_cache table):
   - Written in same transaction as posting
   - Used when Redis is unavailable
   - Ensures correctness even during Redis failure
```

**Why 24-hour TTL?**

Most payment systems retry within minutes. A 24-hour window covers: network timeouts, system restarts, extended retry cycles. After 24 hours, if a caller re-uses the same key for a genuinely different transaction, they should generate a new key anyway (violating idempotency semantics).

**Idempotency DB backup table:** Written in the same PostgreSQL transaction as the posting. If Redis is down, the application falls through to a SELECT on this table — slower but correct. The DB UNIQUE constraint on `postings.idempotency_key` is the final backstop.

---

## Account Metadata Cache

### Cache Key: `account:{account_id}`

### Cache Value:

```json
{
  "account_id": "uuid",
  "account_type": "ASSET",
  "normal_balance": "DEBIT",
  "currency": "INR",
  "status": "ACTIVE"
}
```

### TTL: 60 seconds

### Invalidation: Explicit DEL on status change (FREEZE, CLOSE)

**Why cache account metadata?**

Every posting must validate that all referenced accounts exist and are ACTIVE. Without a cache, a 2-leg posting requires 2 SELECT queries to `accounts` table. At 5,000 postings/sec with avg 2.5 legs each, that's 12,500 DB reads/sec for account validation alone.

**Consistency concern:** If an account is frozen, the cache must be invalidated immediately. The account status change API calls `DEL account:{account_id}` synchronously before returning. Worst case: 60-second TTL expiry for callers that cache-hit a stale ACTIVE status on a newly frozen account.

**Mitigation for high-security freeze:** On account FREEZE, also publish `ledger.account.frozen` Kafka event. Any service that has cached the account status listens to this event and invalidates its local cache.

---

## Redis Cluster Configuration

| Parameter | Value | Reason |
|---|---|---|
| Cluster mode | Enabled (16 shards) | Horizontal scaling, fault tolerance |
| Replication | 1 replica per primary | HA without data loss |
| `maxmemory-policy` | `allkeys-lru` | Evict LRU keys on memory pressure — balance cache is safely re-readable |
| `maxmemory` | 8 GB per node (128 GB total) | Hot balance cache is ~200 MB; plenty of headroom |
| `save` / RDB snapshots | Disabled | Balance cache is ephemeral — DB is the source of truth |
| `appendonly` (AOF) | Disabled for balance cache | Enabled for idempotency cache (separate Redis instance) |

**Two Redis instances (logical separation):**
- `redis-balance`: maxmemory=8GB, no persistence, `allkeys-lru`
- `redis-idempotency`: maxmemory=4GB, AOF enabled (idempotency must survive restarts), `volatile-ttl`

**Why two separate Redis instances?**

The balance cache can be safely lost on Redis restart — reads fall through to DB. The idempotency cache MUST survive restart — if lost, a retry after Redis restart could create a duplicate posting before the DB UNIQUE constraint fires. AOF persistence on `redis-idempotency` ensures keys survive restarts. Mixing policies in one Redis instance is risky.

---

## Cache Warming

On service startup or after Redis flush:

1. Balance cache: not pre-warmed — lazy population on first read
2. Idempotency cache: load last 24h of idempotency keys from DB into Redis on startup (bulk SET with TTL)
3. Account metadata: not pre-warmed — lazy population

**Why warm idempotency on startup?**

If the service restarts during a payment retry storm, the first few milliseconds without a warmed idempotency cache could result in DB UNIQUE constraint errors surfacing to the caller (a 409 Conflict instead of the expected 200 OK). Warming prevents this spike.

---

## Cache Invalidation Failure Scenarios

| Scenario | Risk | Mitigation |
|---|---|---|
| Redis DEL fails after DB commit | Cache serves stale balance for up to TTL=5s | Short TTL ensures eventual consistency |
| Redis unavailable during posting | Idempotency check falls through to DB | DB UNIQUE constraint prevents duplicates |
| Redis restart clears idempotency cache | Retry window: old keys lost → DB UNIQUE constraint enforced | DB idempotency_cache table backup |
| Network partition isolates Redis | Writes buffer in client; reads return cache miss → DB reads | Lettuce auto-reconnect; read fallback to DB |

---

## Cache Hit Rate Targets

| Cache | Expected Hit Rate | Impact if Miss |
|---|---|---|
| Balance cache | 95%+ | DB read: 10–20ms (acceptable) |
| Idempotency cache | 99%+ (retries are rare) | DB read: 5ms (acceptable) |
| Account metadata | 99%+ | DB read: 5ms (acceptable) |

---

## Interview Discussion Points

- **Why not use write-through caching for balances?** Write-through requires atomic update of both DB and cache. Without a distributed transaction, either DB or cache can succeed while the other fails — leaving them inconsistent. Cache invalidation (DEL) ensures the next read always goes to the authoritative DB, eliminating this inconsistency window
- **How do you prevent cache stampede on a hot account balance miss?** Redis single-flight: use `SET balance:{id} NX PX 100` as a lock before the DB read. Only one goroutine/thread fetches from DB; others wait and then read the populated cache. Alternatively, accept the DB query spike — 100 concurrent reads to PostgreSQL for one account is not catastrophic
- **Could you use Hazelcast or Caffeine instead of Redis?** Caffeine (in-process cache) is faster but lost on pod restart. Hazelcast is distributed but adds operational complexity. Redis is the industry standard for distributed caching in Java/Spring systems — operational tooling (RedisInsight, aws ElastiCache) is mature
- **What is the CAP position of your caching strategy?** The cache is the C (Consistency) sacrifice — by accepting eventual consistency in the cache (5s TTL), the system gains availability (balance reads work even if DB is slow). The DB itself is CP — balance correctness is enforced at the journal/snapshot level, not the cache
