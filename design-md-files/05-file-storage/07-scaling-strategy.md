# 07 — Scaling Strategy: File Storage System

## Objective
Define how every layer of the file storage system scales horizontally and vertically — from the CDN edge to the database, from upload ingestion to search. Identify the true bottlenecks at each order of magnitude of growth and the specific interventions required.

---

## Scaling Dimensions

| Axis | Current Load | 10× Scale |
|------|-------------|-----------|
| Upload RPS | 1,200 RPS | 12,000 RPS |
| Download RPS | 12,000 RPS | 120,000 RPS |
| Metadata read RPS | 11,600 RPS | 116,000 RPS |
| Storage writes | 6 GB/s ingress | 60 GB/s ingress |
| Storage egress | 60 GB/s | 600 GB/s |
| Active users | 50M DAU | 500M DAU |
| Total files | 10B | 100B |

---

## Layer 1: CDN and Edge Scaling

### Download Scaling
- Downloads are the dominant load (12,000 RPS, 60 GB/s egress).
- CDN handles virtually all download traffic after first request to origin.
- **CDN hit ratio target**: > 90% for small/medium files; lower for unique large files.
- **Origin shield**: prevents CDN cache miss stampede from hitting S3 directly.

### What CDN Cannot Help With
- Every download of a unique file (first download by anyone) misses cache.
- Large unique files (each user's unique documents) → no CDN benefit, pure origin egress.
- **Mitigation**: Popular shared files (company template, publicly shared document) benefit from CDN. Unique personal files do not. This is the fundamental split between "shared platform" (Dropbox Paper) and "personal storage" (Dropbox).

### Rate Limiting at Edge
- Per-user download rate limit: 1 Gbps sustained, 2 Gbps burst.
- Prevents a single user from monopolizing CDN/origin bandwidth.
- Applied at API gateway (presigned URL generation rate).

---

## Layer 2: Upload Service Scaling

### Horizontal Scaling
- Upload Service is stateless (upload state stored in Redis/PostgreSQL, not in-process).
- 1,200 RPS → easily handled by 10 pods at 120 RPS/pod.
- At 10× (12,000 RPS): 100 pods.
- HPA trigger: CPU > 60% (upload init is CPU-light) or RPS > 1,000/pod.

### The Real Bottleneck: Object Storage Throughput
- S3 has per-prefix throughput limits: 3,500 PUT requests/second per prefix.
- At 12,000 RPS with 10 chunks each → 120,000 chunk PUT requests/second.
- **Solution**: use multiple S3 key prefixes (distribute by hash of userId or random).
  - `users/{shard_0}/`, `users/{shard_1}/`, ... `users/{shard_63}/`
  - Each prefix handles 3,500 PUT/s → 64 prefixes × 3,500 = 224,000 PUT/s capacity.
- This is invisible to the application — Upload Service assigns shard by hashing userId.

### Presigned URL Generation Scaling
- Presigned URL generation is CPU-bound (HMAC-SHA256 signature).
- 12,000 RPS × 10 chunks = 120,000 presigned URLs/second.
- Each URL generation: ~1ms CPU → 120 CPU seconds/second → 120 CPU cores.
- 30 pods × 4 cores each = 120 cores. Horizontal scaling handles this exactly.

---

## Layer 3: Metadata Service Scaling

### Read Scaling (11,600 → 116,000 RPS)
- Read replicas absorb read traffic.
- Tier 1 (hot): Redis cache for most-accessed file metadata.
  - Cache key: `file:metadata:{fileId}` → TTL 60s.
  - Cache hit ratio target: > 80% for file metadata reads.
  - Redis cluster (6 nodes): handles 1M ops/second.
- Tier 2 (warm): PostgreSQL read replicas (3 replicas).
  - Each replica handles ~30,000 RPS.
  - 3 replicas × 30,000 = 90,000 RPS capacity.
- At 10× scale: add read replicas or shard.

### Write Scaling (1,200 → 12,000 RPS writes)
- Writes to PostgreSQL primary: INSERT file, INSERT file_version, UPDATE quota.
- PostgreSQL primary handles ~20,000 write transactions/second at sustained load.
- 12,000 write RPS is within primary capacity.
- **Bottleneck appears**: at ~50,000 write RPS → shard with Citus.

### Hot File Problem
- A single file shared with 1M people → 1M concurrent downloads → all metadata reads for one `fileId`.
- Redis cache with TTL eliminates DB load for this case entirely.
- Cache key: `file:metadata:{fileId}` — shared across all users requesting same file.
- On file update/delete → Redis key invalidated immediately.

### Folder Listing Performance
- `SELECT * FROM files WHERE parent_folder_id = $1 AND status = 'ACTIVE' ORDER BY name`
- Index: `(parent_folder_id, status, name)`.
- For a folder with 100K files → index scan is fast.
- For a folder with 1M+ files → paginate with cursor (keyset pagination on `file_id`).

---

## Layer 4: Search Scaling

### Elasticsearch Scaling
- Index size: 100B files × avg 500 bytes metadata = 50 TB index.
- No single Elasticsearch cluster handles 50 TB efficiently.
- **Sharding**: per-user index (`files_user_{userId_hash_mod_100}`) — 100 index groups.
- Each group: ~1 TB. 10 ES nodes per group = 10 TB cluster.
- Total: 100 groups × 10 nodes = 1,000 ES nodes (only needed at 100B files — Taking scale).

### Practical MVP/V1 Approach
- Single ES cluster, 3–5 nodes.
- All users' files in one index with `user_id` as a filter field.
- Handles up to ~100M files comfortably.
- At 1B files: introduce per-shard indexes.

### Search Query Optimization
- Full-text search is expensive at scale.
- Cache popular search queries per user: `search:{userId}:hash(query)` → TTL 30s.
- Autocomplete: separate index with just file names and IDs (smaller, faster).

---

## Layer 5: Storage (S3) Scaling

### S3 is Effectively Unlimited
- S3 scales storage automatically — no capacity planning needed for storage bytes.
- Throughput: limited by prefix RPS (addressed above) and total account limits.
- Cost: $0.023/GB/month (S3 Standard). 540 PB = $12.4M/month — tiered storage critical.

### Storage Tiering
| Tier | Storage Class | Access Pattern | Cost |
|------|--------------|----------------|------|
| Hot | S3 Standard | Accessed in last 30 days | $0.023/GB/month |
| Warm | S3 Standard-IA | Accessed in last 180 days | $0.0125/GB/month |
| Cold | S3 Glacier Instant | Rarely accessed, fast restore needed | $0.004/GB/month |
| Archive | S3 Glacier Deep Archive | > 1 year old, large files | $0.00099/GB/month |

Lifecycle policy automates tier transitions based on last access timestamp.

### Intelligent Tiering
- S3 Intelligent-Tiering: AWS automatically moves objects between tiers based on access.
- Cost: $0.0025/1,000 objects/month monitoring fee.
- At 100B files: $250,000/month monitoring fee — may be cheaper to manage lifecycle rules manually.

---

## Layer 6: Sync Service Scaling

### Change Feed Architecture
- Sync clients poll at most once every 10 seconds (not every second — battery drain).
- At 50M DAU × 1 poll/10s = 5M RPS on Sync Service.
- This is high — must be aggressively cached.

### Optimization
- Change feed cursor is per-device, stored in Redis.
- If no new events since cursor → return empty (304 Not Modified) without DB query.
- Only on cursor miss (new changes) → query `sync_events` table.
- Redis check: `GET sync:user:{userId}:hasChanges:{cursor}` → Boolean.
- If true → fetch from DB. If false → return 304.
- Expected ratio: 90% of polls return no changes → 90% of requests served from Redis at < 1ms.
- Remaining 10%: 500,000 RPS to DB → still high → partition `sync_events` by `user_id`.

---

## Layer 7: Queue (Kafka) Scaling

### Throughput
- `file-metadata-events`: 1,200 events/second × 3 consumers (search, sync, preview) = 3,600 event deliveries/second per partition group.
- 64 partitions handles this comfortably (each partition: ~56 events/second).
- At 10×: increase partitions to 128.

### Consumer Lag Management
- Alert if consumer lag > 10,000 messages on any topic.
- Search indexer lag = search results are stale. SLO: < 30 seconds indexing lag.
- Sync consumer lag = sync clients are delayed. SLO: < 10 seconds sync lag.

---

## Scaling Evolution Summary

| Phase | Scale | Key Scaling Actions |
|-------|-------|-------------------|
| MVP | 100K DAU | Single DB, 2 app servers, basic CDN |
| V1 | 1M DAU | Read replicas, Redis cache, CDN with origin shield |
| V2 | 10M DAU | ES cluster, Kafka, multiple read replicas, S3 tiering |
| V3 | 100M DAU | DB partitioning, Redis cluster, Vitess sharding hint |
| Taking | 500M DAU | Per-user ES shards, Citus/Vitess, 1,000+ ES nodes |

---

## Interview-Level Discussion Points

- **What's the first bottleneck at 10× scale?** — Metadata database reads (11,600 → 116,000 RPS). Redis cache solves most of it (hot files). For reads that miss cache: read replicas (3 → 5). Write bottleneck doesn't appear until 50,000 write RPS.
- **How do you prevent cache stampede on a popular shared file?** — When the Redis TTL expires on a hot file, 1,000 concurrent requests may all miss cache simultaneously. Solution: probabilistic early expiration (jitter the TTL by ±10%) + request coalescing (first request fetches, others wait). Spring Cache `@Cacheable` doesn't do coalescing — implement with Redisson distributed lock.
- **Why does the sync service poll instead of push?** — Push (WebSocket/SSE) from server to 50M clients = 50M persistent connections. Stateful, expensive. Poll: stateless, scales horizontally, battery-friendly with long poll (30s timeout). Google Drive and Dropbox both use polling for sync clients.
- **How do you scale deduplication at 100B chunks?** — The `chunks` table becomes a bottleneck (100B rows, hot row per popular chunk). Bloom filter in Redis for existence check (1.2 GB at 1% FPR). Only confirm in DB on Bloom filter hit. 99% of "chunk not found" queries never hit the DB.
