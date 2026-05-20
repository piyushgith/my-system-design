# 07 — Scaling Strategy: Pastebin / Code Sharing Platform

---

## Objective

Define the scaling evolution from a single-instance MVP to a globally distributed platform. Identify bottlenecks at each scale tier, the solutions applied, and the operational cost of each step.

---

## Scaling Tiers

| Tier | Scale | Architecture | Primary Bottleneck |
|------|-------|-------------|-------------------|
| T0: MVP | < 1 RPS | Single instance + PostgreSQL | N/A |
| T1: Early | 1–10 RPS | + Redis cache + Read replica | Cache misses |
| T2: Growth | 10–100 RPS | + CDN + Kafka async | DB writes, content delivery |
| T3: Scale | 100–1000 RPS | + Horizontal app scale + S3 direct upload | DB connections, S3 bandwidth |
| T4: Global | 1000+ RPS | + Multi-region + Edge caching | Cross-region latency, consistency |

---

## Read Scaling

### The Read Problem

Pastebin is read-heavy (10:1 read:write). Most reads are for **recently created, popular pastes** — highly cacheable. The challenge is serving read traffic without hitting the database on every request.

### Layer 1: CDN (Public Pastes Only)

**Who serves it:** CloudFront / CloudFlare at 200+ PoPs globally.

**What is cached:** All `GET /raw/{key}` and rendered paste views for PUBLIC pastes.

**Cache-Control strategy:**
```
Paste expires in 1 hour:   Cache-Control: public, max-age=3600, s-maxage=3600
Paste expires in 1 day:    Cache-Control: public, max-age=86400, s-maxage=86400
Paste NEVER expires:       Cache-Control: public, max-age=604800, s-maxage=2592000
```

**Cache Invalidation (on paste deletion/expiry):**
- Immediately: API call to CDN invalidation endpoint (CloudFront CreateInvalidation)
- CloudFront invalidation latency: ~5–30 seconds globally
- Cost: CloudFront charges per invalidation path (first 1,000/month free; $0.005 per path beyond)

**What CDN cannot cache:**
- PRIVATE and UNLISTED pastes (cannot be cached — different content per requester)
- Authenticated requests
- API metadata endpoints (`/api/v1/pastes/{key}` JSON response)

**Expected CDN hit ratio:** 70–80% of total read traffic (public pastes are the majority).

---

### Layer 2: Redis Application Cache

**Who serves it:** Redis Cluster (3 shards, 6 nodes for HA) — deployed in same region as application.

**What is cached:**
- Paste metadata + small content (< 1 KB): full JSON response
- Paste metadata only for large pastes (content fetched from S3 separately)
- Negative cache: `paste_not_found:{key}` with short TTL (60s) — prevents DB hammering on invalid keys

**Cache key design:**
```
paste:metadata:{shortKey}    → JSON metadata, TTL = min(expires_at - now, 3600s)
paste:content:{shortKey}     → Raw content (only for inline/small pastes), same TTL
paste:not_found:{shortKey}   → Sentinel value, TTL = 60s
rate_limit:{ip}:{window}     → Counter, TTL = window duration
idempotency:{key}            → Response JSON, TTL = 86400s
```

**Cache Invalidation:**
- On DELETE: `DEL paste:metadata:{key} paste:content:{key}`
- On expiry event: same DEL commands
- On view count update: no cache update (view count is eventually consistent in DB anyway)

**Expected Redis hit ratio:** 85%+ for hot pastes (pareto distribution — top 1% of pastes get 80% of reads).

**Redis failure mode:** On Redis cluster failure, all reads fall through to PostgreSQL. This is the **cache stampede** scenario.

---

### Cache Stampede Prevention

When Redis cache is cold (restart, new deploy) or a hot paste's cache entry expires, thousands of concurrent requests may hit PostgreSQL simultaneously.

**Solutions:**

**Option A: Mutex/Lock (Chosen for MVP)**
```
1. Cache miss detected
2. Acquire Redis lock: SET lock:paste:{key} 1 NX EX 5
3. If lock acquired: fetch from DB, warm cache, release lock
4. If lock NOT acquired: wait 100ms, retry cache read
5. After cache warm: all waiters served from cache
```

**Option B: Probabilistic Early Re-computation**
- Before TTL expires, proactively re-warm the cache based on access frequency
- More complex; appropriate for T3+ scale

**Option C: Stale-while-revalidate**
- Serve stale cache content while asynchronously refreshing
- Requires dual TTL (stale TTL >> fresh TTL)

---

## Write Scaling

### The Write Problem

The write path is simpler but has multiple sequential operations:
1. Validate input
2. Generate unique short key
3. Upload content to S3 (for pastes > 1 KB)
4. Insert metadata to PostgreSQL
5. Publish event to Kafka (via outbox)

S3 upload is the slowest step for large pastes (10 MB upload at 100 Mbps = ~0.8 seconds).

### Pre-signed S3 Upload (Solving S3 Write Bottleneck)

**Problem:** Client → Application → S3 routes large file through the application server, consuming memory and CPU.

**Solution: Client-Direct S3 Upload**

```
Flow:
1. Client: POST /api/v1/pastes/upload-url (metadata only)
2. App: generate S3 pre-signed URL (PUT), valid 5 minutes
3. App: return pre-signed URL to client
4. Client: PUT content directly to S3 using pre-signed URL
5. Client: POST /api/v1/pastes/confirm {s3Key, metadata}
6. App: verify S3 object exists, save metadata to DB, publish event
```

**Benefits:**
- Application server memory/CPU not consumed by large file upload
- S3 handles bandwidth (multi-Gbps)
- Application can scale independently of content size

**Tradeoff:**
- 2 API calls instead of 1 (complexity for client)
- Client must handle S3 upload separately
- Pre-signed URL timeout can cause failures if client is slow

**When to adopt:** When average paste size exceeds 100 KB OR application instances hit memory pressure during uploads.

---

### Horizontal Application Scaling

Application instances are stateless (no local state — session data in JWT, cache in Redis):

```
Kubernetes HPA (Horizontal Pod Autoscaler):
  minReplicas: 2
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        targetAverageUtilization: 60
    - type: External
      external:
        metricName: kafka_consumer_lag
        targetAverageValue: 1000
```

**Connection pool per instance:**
- HikariCP: 10 connections per instance
- At 20 instances: 200 total DB connections
- PostgreSQL max_connections default: 100 — must be tuned or use PgBouncer

**PgBouncer (connection pooler):**
- Sits between application and PostgreSQL
- Transaction-mode pooling: 200 app connections served by 20 PostgreSQL connections
- Mandatory at T2+ scale

---

## Database Scaling

### Stage 1: Single Primary (MVP)
- All reads and writes to primary
- Limitation: ~1,000 TPS on a c5.xlarge

### Stage 2: Read Replica (T1)
- Add 1 read replica (streaming replication, < 10ms lag)
- Route user paste lists and analytics to replica
- Primary serves writes and hot reads

### Stage 3: Multiple Read Replicas (T2)
- Add 2-3 read replicas behind a load balancer
- Use ProxySQL or HAProxy for read routing
- Route analytics queries to dedicated replica (avoid affecting paste reads)

### Stage 4: Functional Partitioning (T3)
- Move analytics tables to a separate PostgreSQL instance (or TimescaleDB)
- `paste.pastes` and `identity.*` remain on primary cluster
- Eliminates analytics query interference with production reads

### Stage 5: Sharding Consideration (T4)
At 1 billion+ paste records, horizontal sharding becomes necessary:

**Shard key: `short_key` (first 2 chars)**
- 36^2 = 1,296 possible shard keys
- Distribute across N shards using consistent hashing
- Problem: cross-shard queries (user paste list requires scatter-gather)

**Alternative: shard by `owner_id`**
- User's paste list queries go to single shard
- Anonymous pastes distributed by `short_key`
- Hotspot risk: viral users get uneven shard distribution

**Recommendation:** Avoid sharding until absolutely necessary. PostgreSQL with proper indexing handles 100M+ rows well. Partition by `created_at` first.

---

## CDN and Edge Strategy

### Content Routing by Paste Type

| Paste Type | Served By | Latency |
|-----------|----------|---------|
| PUBLIC raw content | CDN edge | ~5ms global |
| PUBLIC metadata (JSON) | CDN edge or origin | ~50ms |
| UNLISTED content | Origin (no CDN cache) | ~100ms |
| PRIVATE content | Origin (auth check required) | ~100ms |

### Multi-Region Strategy (T4)

```
Primary Region: us-east-1 (writes, master DB)
Read Region:    eu-west-1 (read replica, EU users)
Read Region:    ap-southeast-1 (read replica, Asia users)

Traffic routing: Latency-based DNS (Route 53)
Writes: Always routed to primary region (accept higher write latency)
Reads: Routed to nearest region (may read stale data for seconds)
```

**Cross-region replication lag:** PostgreSQL streaming replication across regions typically adds 50–200ms lag. For a Pastebin, this is acceptable (user reads their own paste from the primary region after creation).

---

## Expiry Cleanup Scaling

At 10M pastes/month with a 60% expiry rate = 6M expirations/month = 200,000/day = ~2-3/second.

**Cleanup job throughput:**
- Single instance: 1,000 expirations/minute (S3 delete bottleneck)
- Need: 140 expirations/minute = well within single instance capacity

**Scaling the cleanup job:**
- `FOR UPDATE SKIP LOCKED` allows multiple instances to work in parallel
- Run 2 cleanup instances for HA (one active, one standby essentially)
- If throughput exceeds 10,000/minute: increase to 5-10 parallel cleanup workers (each taking different partition)

**S3 rate limits:**
- S3 supports 3,500 PUTs/second and 5,500 GETs/second per prefix
- Distribute S3 keys across prefixes to avoid hot partition: `pastes/{shard}/{date}/{key}`

---

## Rate Limiting at Scale

### MVP: Redis sliding window per IP
```
Key: rate_limit:{ip}:{minute_bucket}
Algorithm: INCR + EXPIRE per window
```

### T2+: Distributed rate limiting with Redis Cluster
```
Use token bucket algorithm
Keys spread across Redis shards by IP hash
Lua script for atomic check-and-decrement
```

### T3+: API Gateway-level rate limiting
- Move rate limiting to Kong or AWS API Gateway
- Offloads rate limit logic from application entirely
- Supports more sophisticated policies (per API key, per tier)

---

## Bottleneck Analysis

| Scale | Bottleneck | Solution |
|-------|-----------|---------|
| MVP | None significant | N/A |
| 10 RPS reads | DB read load | Redis cache |
| 100 RPS reads | Network bandwidth | CDN |
| 500 RPS reads | Cache stampede | Mutex + probabilistic re-warm |
| 100 RPS writes | S3 upload latency | Pre-signed client-direct upload |
| 500 RPS writes | DB connection pool | PgBouncer + connection pooling |
| 1000 RPS writes | Single DB primary | Read replicas + CQRS reads |
| 10,000+ RPS | DB throughput ceiling | Horizontal sharding (last resort) |

---

## N+1 Query Risks

### User Paste List (Classic N+1)
```
BAD:
  SELECT * FROM pastes WHERE owner_id = ?  → 20 pastes
  For each paste: SELECT display_name FROM users WHERE id = ?  → 20 extra queries!

GOOD:
  SELECT p.*, u.display_name FROM pastes p
  LEFT JOIN users u ON u.id = p.owner_id
  WHERE p.owner_id = ?
  ORDER BY p.created_at DESC LIMIT 20;
```

Spring Boot with JPA: use `@EntityGraph` or `JOIN FETCH` to prevent lazy loading N+1.

### Version History (N+1 on versions)
```
BAD: fetch paste → fetch versions lazily per paste
GOOD: batch fetch versions for all paste IDs in one query
```

---

## Interview Discussion Points

- What is a cache stampede? How do you prevent it in a read-heavy system?
- At what point does the CDN cache invalidation strategy become a bottleneck?
- How do you scale the expiry cleanup job without creating a thundering herd on PostgreSQL?
- Why is PgBouncer needed even though HikariCP already pools connections?
- What is `FOR UPDATE SKIP LOCKED` and why is it better than row-level locking for batch jobs?
- What are the tradeoffs between sharding by short_key vs sharding by owner_id?
- How would you handle a paste that suddenly goes viral (10,000 concurrent reads for a newly created paste with no CDN cache yet)?
