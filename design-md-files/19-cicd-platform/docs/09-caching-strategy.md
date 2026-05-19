# 09 — Caching Strategy: CI/CD Platform

---

## Objective

Define caching layers across the CI/CD platform to reduce latency, database load, and build times. Covers infrastructure caching (Redis/CDN) and build-time caching (dependency and layer caches unique to CI).

---

## Caching Layers

| Layer | Store | What's Cached | TTL |
|---|---|---|---|
| API response cache | Redis | Run status, job lists (per repo) | 5s |
| Workflow YAML cache | Redis + S3 | Parsed workflow per commitSha | Permanent (immutable) |
| Runner label → runner ID | Redis | Available runner routing | 30s |
| Org concurrency counter | Redis | Active job count per org | TTL: job duration |
| Log line buffer | Redis | Last 5000 lines per running job | 4 hours |
| Secret access tokens | Redis | Job-scoped token validation cache | 15 min (token TTL) |
| Runner metadata | Redis | Runner status, last heartbeat | 60s |
| Build dependency cache | Runner node local + S3 | node_modules, Maven .m2, pip | Configurable |
| Docker layer cache | Runner node local + registry | OCI image layers | 7 days |
| Static assets (UI) | CDN | React app bundles | 1 year (immutable) |

---

## API Response Caching

### Run Status — Short TTL

Run status queries are the most frequent API calls:
```
GET /v1/runs/{runId} → Redis cache key: run:{runId}
TTL: 5 seconds

Cache invalidation: Scheduler writes to DB AND publishes invalidation event
Kafka topic: cache-invalidations → Cache Invalidation Service → Redis DEL
```

**Why 5 seconds, not write-through?** Write-through would require all state transition writers to update Redis. With multiple Scheduler instances (future), cache coherency is complex. Short TTL is simpler — 5 seconds stale data is acceptable for a status dashboard.

### Run List (repo level) — Paginated

```
Key: runs:list:{repoId}:{statusFilter}:{cursor}
TTL: 10 seconds
Population: on cache miss, query PostgreSQL read replica

New run created? → DEL runs:list:{repoId}:* (pattern delete via SCAN)
```

**Warning on pattern delete:** `SCAN + DEL` for pattern `runs:list:{repoId}:*` is O(n) on Redis key space. Alternative: include last-modified timestamp in cache key → old key naturally expires.

---

## Workflow YAML Cache

Pipeline definitions are fetched from source control on every run trigger. At 1000 triggers/sec, hitting GitHub API directly would exhaust rate limits and add ~200ms latency.

### Cache Key: `workflow:{repoId}:{commitSha}:{workflowPath}`

```
Cache population:
  Scheduler → Pipeline Config Service
  Config Service → GitHub API: GET /repos/{owner}/{repo}/contents/.github/workflows/ci.yml?ref={sha}
  Config Service → Parse YAML → Validate schema
  Config Service → Store in Redis (TTL: 7 days) AND S3 (permanent)

Cache hit:
  Config Service → Redis → Return cached parsed Workflow object

Immutability:
  commitSha is immutable — same SHA always produces same YAML
  Cache entry is permanent (never invalidated, only TTL expiry in Redis)
  S3 is fallback if Redis entry expired
```

**Storage size:** Parsed Workflow JSON object ≈ 5–50 KB. At 1M unique commit SHAs in Redis (7-day window): 5–50 GB Redis memory. Use S3 as primary cold storage; Redis as hot cache.

---

## Org Concurrency Counter (Redis Atomic)

Scheduler enforces per-org concurrent job limits without a DB round-trip per job:

```
Job start:
  count = INCR cicd:concurrency:{orgId}
  if count > org.concurrent_job_limit:
    DECR cicd:concurrency:{orgId}
    return QUOTA_EXCEEDED

Job complete/fail/cancel:
  DECR cicd:concurrency:{orgId}
```

**TTL safety net:** Set expiry `EXPIRE cicd:concurrency:{orgId} 3600` on each INCR. If a job crashes without decrementing, counter naturally expires in 1 hour. Prevents permanent quota lock.

**Consistency concern:** What if Redis restarts and counter resets to 0? All orgs appear to have 0 active jobs → burst of jobs dispatched until system catches up. Mitigation: on startup, warm Redis counters from `SELECT org_id, COUNT(*) FROM jobs WHERE status='RUNNING'`.

---

## Build Dependency Caching (Build-Time Cache)

This is the most impactful caching for developer productivity — caching `node_modules`, Maven `.m2`, pip packages.

### Cache Key Strategy

```yaml
# Pipeline YAML
steps:
  - name: Cache node_modules
    uses: cache@v2
    with:
      key: npm-${{ runner.os }}-${{ hashFiles('package-lock.json') }}
      restore-keys:
        - npm-${{ runner.os }}-
      path: node_modules

  - name: Install dependencies
    run: npm ci
    if: cache.hit == false
```

**Cache key components:**
- `runner.os`: different OS = incompatible binaries
- `hashFiles('package-lock.json')`: changes when dependencies change
- `restore-keys`: fallback to partial match (older lock file version)

### Cache Backend

```
Cache upload (post-job):
  Runner: POST /v1/cache/upload
  Body: {key: "npm-ubuntu-abc123", content: tar.gz of node_modules}
  Artifact Service → S3: cache/{orgId}/{key}.tar.gz

Cache download (pre-job):
  Runner: GET /v1/cache/{key}
  Cache Service: S3 GET presigned URL
  Runner: download + extract
  
Cache hit rate target: > 80% for active projects
Cache size limit: 10 GB per project
LRU eviction: least-recently-accessed keys deleted first when quota exceeded
```

**Storage:** S3 with 7-day lifecycle. Cache content is reproducible — deletion is safe, just adds build time.

### Build Time Impact

| Scenario | Without Cache | With Cache | Savings |
|---|---|---|---|
| Node.js (1023 packages) | 45s npm ci | 3s extract | 93% |
| Maven (200 dependencies) | 3 min download | 5s extract | 97% |
| Python pip (100 packages) | 90s pip install | 4s extract | 96% |
| Docker image build | 2 min (no layer cache) | 15s (cache hit) | 87% |

---

## Docker Layer Cache

Docker builds are cached at the layer level. Each `RUN` instruction in a Dockerfile creates a layer — cached if input is unchanged.

### Remote Cache Backend (BuildKit)

```yaml
- name: Build Docker image
  run: |
    docker buildx build \
      --cache-from type=registry,ref=registry.example.com/myapp:cache \
      --cache-to type=registry,ref=registry.example.com/myapp:cache,mode=max \
      -t myapp:${{ commitSha }} .
```

**How it works:**
- `--cache-from`: BuildKit pulls layer cache from registry before build
- `--cache-to`: After build, push new/changed layers to registry cache
- `mode=max`: Cache all intermediate layers (not just final image)

**Savings:** First build of Dockerfile = 2 min. With layer cache (only `COPY` and later layers changed): 15 seconds.

**Storage:** OCI registry (Harbor, ECR). Layer deduplication built-in. 7-day TTL for unused layers.

---

## CDN Caching (Static Assets)

```
React app → Build → /dist folder
/dist/*.js: content-hashed filenames (e.g., app.a3f9b2.js)
/dist/index.html: short cache (5 min — references hashed assets)

CDN (CloudFront/Fastly):
  *.js, *.css: Cache-Control: max-age=31536000, immutable
  index.html: Cache-Control: max-age=300, no-cache
  
New deploy:
  index.html fetched fresh → points to new hashed JS/CSS bundles
  Old bundles: immediately served from cache (still valid hash names)
```

**Result:** Zero downtime deploys. Old clients get old bundle (still valid). New clients get new bundle. No cache purge needed.

---

## Log Buffer Caching (Redis)

Already detailed in event flow, summarized here:

```
Live job logs: Redis List (RPUSH per log chunk)
Eviction: LTRIM to last 5000 lines (circular buffer)
TTL: 4 hours (completed job log in S3; Redis buffer cleaned up)
SSE consumer: LRANGE for initial replay, SUBSCRIBE for new lines
```

**Memory calculation at scale:**
```
10K concurrent jobs × 5000 lines × avg 200 bytes = 10 GB Redis memory
6-node Redis Cluster (3 primary × 32 GB RAM each) → 96 GB total capacity
Headroom: 9.6x
```

---

## Cache Invalidation Patterns

### Write-Through vs TTL-Based

| Data | Strategy | Why |
|---|---|---|
| Run status | TTL (5s) | Low consistency requirement; simple |
| Workflow YAML | Immutable | commitSha = immutable key; never invalidated |
| Org concurrency counter | Write-through | Must be accurate for quota enforcement |
| Runner status | TTL (30s) | Staleness OK for routing; updated on heartbeat |
| Secrets | No cache (read from DB always) | Must be consistent; rotation must take effect |

### Thundering Herd Prevention

When Redis TTL expires for a popular run, many simultaneous requests hit PostgreSQL:

```
Solution: Cache stampede protection with lock
  1. Thread 1: cache miss → acquire distributed lock (SET NX)
  2. Thread 1: query DB → populate cache
  3. Threads 2-N: lock held by Thread 1 → wait → Thread 1 releases → cache hit
  
Alternative: Probabilistic early expiration
  Before TTL expires, proactively refresh based on: 
  if (ttl < max_ttl * random()) → refresh
  Prevents simultaneous expiry for many requests
```

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| 5s TTL for run status | Simple, no explicit invalidation | Stale status UI for up to 5s — acceptable |
| Immutable workflow YAML cache | Zero invalidation complexity | Redis memory grows with unique SHAs; S3 fallback needed |
| Build dependency cache via S3 | Large objects (100s MB); S3 is cheap | Download latency (S3 GET ~50ms) vs no cache benefit |
| Docker layer cache via registry | BuildKit native; automatic layer dedup | Registry storage costs; cache warm-up on first build |
| Redis for concurrency counters | Atomic operations sub-ms | Redis restart resets counters; need warm-up on restart |

---

## Interview Discussion Points

- **What is the hardest caching problem in a CI/CD platform?** Build dependency caching. The key must perfectly capture all inputs that affect the output (OS, lock file, environment). A bad cache key causes: false hits (stale packages) or false misses (unnecessary downloads). `hashFiles()` on lock files is the standard solution
- **How do you handle cache poisoning (malicious cache upload)?** Caches are scoped to the org. Only runs from the same org can read/write caches. A branch can poison its own branch cache but not `main` branch cache (write-protect `main` caches to only main branch runs). Hash verification: download cache → verify SHA256 matches stored hash before extracting
- **Why not cache secrets in Redis?** Secrets must be fetched from source of truth (PostgreSQL + KMS) on every job start to ensure rotation takes effect immediately. A cached secret that was rotated would be served stale — security incident. Consistency > performance for secrets
- **How do you size the build dependency cache?** Per-project quota (10 GB default). LRU eviction — cached versions not accessed in 7 days deleted. High-traffic projects hit cache frequently → cache stays warm. Low-traffic projects → cache may be evicted before next run → cold start penalty. Solution: increase quota for active projects
