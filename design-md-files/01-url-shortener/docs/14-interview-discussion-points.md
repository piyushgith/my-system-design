# 14 — Interview Discussion Points: URL Shortener

---

## Objective

Prepare for FAANG-level system design interview discussions — covering common interviewer follow-ups, tradeoff deep dives, scaling evolution questions, and staff/principal engineer discussion points.

---

## Common Interviewer Questions

### Round 1: Fundamentals (Senior Engineer Level)

**Q: How do you generate the short code?**

> Strong answer: Random 6-character Base62 string from `[a-zA-Z0-9]` (62^6 ≈ 56B unique codes). Insert with `ON CONFLICT` constraint — retry on collision. At 5M creations/day, collision probability is negligible (~0.009% per attempt). Alternative: pre-generated key pool (KGS) eliminates retries at cost of operational complexity. Avoid sequential IDs — they're guessable (enumerable attacks).

**Q: 301 vs 302 — which one and why?**

> Strong answer: 302 by default. 301 gets cached by browser and CDN — you lose analytics visibility permanently. Once a user's browser caches the 301, future redirects bypass the system entirely. We use 302 so every redirect hits our origin or CDN, enabling click tracking. For explicitly permanent, high-traffic URLs with no analytics requirement, we offer opt-in 301 with CDN-level analytics as compensation.

**Q: How do you store and retrieve the mapping efficiently?**

> Strong answer: Multi-layer cache: CDN (for 301 redirects) → local in-process Caffeine cache (hot URLs, 60s TTL) → Redis distributed cache (24h TTL) → PostgreSQL read replica (source of truth). Short code as primary key in Postgres (B-tree index = O(log n) lookup). With > 95% Redis hit rate, most redirects never touch Postgres. Redirect p99 target: < 50ms.

**Q: How does your system handle URL expiration?**

> Strong answer: Dual mechanism. (1) Redis TTL: set `SETEX url:{code} {ttlSeconds} {data}` at creation — Redis naturally evicts the key at expiry. First redirect after expiry is a cache miss → Postgres lookup → check `expiresAt < now()` → return 410 Gone. (2) Background expiration job: runs every 60s, `UPDATE short_urls SET status = 'EXPIRED' WHERE expires_at < NOW() AND status = 'ACTIVE'` — keeps DB in sync. For click-limit expiration: Redis INCR counter → atomic check → evict on limit reached.

---

### Round 2: Scalability (Senior/Staff Engineer Level)

**Q: System is getting 10x the traffic. What breaks first?**

> Redis becomes the bottleneck first. At 10x RPS (100K redirects/sec), Redis handles ~90% of requests (cache hits). A single Redis node handles ~100K operations/sec — we're at the limit. Solutions in order: (1) Add Redis read replicas (read distribution), (2) Local Caffeine cache (eliminate network hop for hot URLs), (3) Redis Cluster (horizontal sharding). Second bottleneck: Postgres connection pool (PgBouncer absorbs this). Third: Kafka (can handle 10M+ events/sec — not the bottleneck here).

**Q: How would you design for 100x traffic (FAANG-scale)?**

> Multi-region active-active with CDN at the core. CDN handles 80% of redirects from edge nodes globally (cache hit rate target: 80%). Remaining 20% served from nearest regional cluster (3 regions: US, EU, AP). Each region has: local Redis cluster, local Postgres read replica, local Kafka cluster. URL creation still writes to a single global primary (accepting replication lag of < 1s). For truly extreme scale: CloudFront Functions or Lambda@Edge for zero-latency redirects (edge compute, no origin hit for cached URLs).

**Q: How do you handle a viral URL (10M redirects in 1 hour for one URL)?**

> Single short code = single Redis key = potential hot key problem. At 10M redirects/hour = ~2,800 RPS on one key, one Redis node saturates. Solutions: (1) Local Caffeine cache per pod (5-second TTL): with 20 pods, each local cache handles 140 RPS — Redis sees only 20 × (1 request/5s) = 4 RPS per URL. (2) Redis read replicas: 3 read replicas distribute the load. (3) Auto-detect hot keys via Redis `MONITOR` + promote to local cache on hot key detection.

**Q: How do you scale the analytics pipeline?**

> Analytics is decoupled via Kafka — it doesn't affect redirect latency. Kafka scales to 10M+ events/sec. ClickHouse scales horizontally (distributed tables across multiple nodes). Consumer pods scale via KEDA based on Kafka consumer lag. The bottleneck in analytics is usually ClickHouse write throughput — mitigate with large batched inserts (10K rows per INSERT). For FAANG-scale analytics (1B clicks/day = 11K events/sec), ClickHouse cluster with 3 shards × 2 replicas is sufficient.

---

### Round 3: Distributed Systems Deep Dive (Staff Engineer Level)

**Q: How do you guarantee exactly-once URL creation?**

> Two levels: (1) Database level: PostgreSQL unique constraint on `short_code` (and `idempotency_key`). Duplicate creation returns existing URL. (2) Client level: client sends `Idempotency-Key: UUID`. Server stores key → response mapping in Redis (24h TTL). Duplicate requests within 24h return cached response. Beyond 24h, key expires — rare scenario, acceptable. For the custom alias case: `INSERT ... ON CONFLICT (short_code) DO NOTHING` — atomically enforces uniqueness.

**Q: How do you handle distributed transactions? (URL creation + cache warm + event publish)**

> Outbox pattern. In a single DB transaction: (1) INSERT into `short_urls`, (2) INSERT into `outbox_events`. After commit, a background publisher polls outbox, publishes to Kafka, marks published. This guarantees: URL is never created without an event eventually being published. Event may be published multiple times (at-least-once) — cache warmer handles this idempotently (Redis SET is idempotent). Alternative: Debezium CDC reads Postgres WAL as the outbox — no polling overhead, sub-100ms event latency.

**Q: How does the system behave during a network partition?**

> URL Shortener is AP (Availability + Partition Tolerance) for reads, CP (Consistency + Partition Tolerance) for writes. During a network partition between regions: redirect service in each region continues serving from local Redis + read replica (availability). New URL creation may fail if partition isolates the primary DB — creation returns 503 (consistency over availability for writes). Analytics events buffer in Kafka and catch up after partition heals. This is the right tradeoff: users can always follow links; creators can wait.

**Q: Explain the CAP theorem implications for your design.**

> We can't have all three. For the redirect path: we choose A (availability) over C (consistency). Stale cached data is acceptable — user follows a link to a slightly outdated destination. For URL creation uniqueness: we choose C (consistency) — a duplicate alias must never be created. Partition tolerance is assumed (it's a distributed system by definition). The interesting tradeoff: a user deletes a URL, but for 60 seconds (Caffeine TTL) it may still redirect. This is a deliberate AP choice — the alternative (strict consistency on every redirect) would require a distributed lock on every request, destroying performance.

---

### Round 4: Security & Reliability (Staff/Principal Level)

**Q: How do you prevent phishing via short URLs?**

> Defense in depth: (1) Async malware/phishing scan at creation time via Google Safe Browsing API — flag and block URLs matching known threats. (2) Internal blocklist of known bad domains, updated daily from threat intelligence feeds. (3) User abuse reporting — users can flag a URL; threshold triggers auto-block + admin review. (4) Rate limiting for anonymous URL creation — makes bulk phishing campaign creation expensive. (5) Domain reputation scoring — newly registered domains get elevated scrutiny. (6) At redirect time: check blocklist in Redis (zero-latency check, pre-loaded in cache). The key insight: you can't prevent all abuse, but you can make it expensive and detectable.

**Q: How do you handle GDPR "right to be forgotten"?**

> User account deletion triggers: (1) Soft delete user record immediately. (2) All associated short URLs: status = DELETED, ownerId = NULL (disassociate). (3) Click event data: anonymize immediately (zero out IP octets) — don't wait for scheduled anonymization. (4) Redis eviction of user's URL cache entries (batch DEL operation). (5) Publish `UserDataErased` event — consumers confirm erasure within 24h. S3 archived data: separate erasure job via S3 Batch Operations. Analytics aggregates are retained (no personal data in counts/percentages).

**Q: What happens during a bad deployment that breaks redirects?**

> Canary deployment limits blast radius to 5% of traffic. Automated rollback triggers if: error rate > 1% OR p99 latency > 2x baseline within 15-minute window. Prometheus alert fires → PagerDuty → on-call engineer → can trigger manual rollback in < 2 min via CI/CD pipeline. Kubernetes rolling deployment ensures: always (replicas - maxUnavailable) pods running the old version until new version is validated by readiness probes. Blue-green deploy possible for large changes (switch 0% → 100% instantly with instant rollback capability).

---

### Staff/Principal Discussion Points

**Q: How would you evolve this from a monolith to microservices?**

> Extract in order of isolation and scaling justification, not arbitrarily: (1) Redirect service first — highest RPS, most independence, stateless. Extract with a feature flag: route 5% of redirects to new service, validate SLOs, increase to 100%. (2) Analytics consumer second — completely independent, different scaling profile, already has Kafka as the interface boundary. (3) User/Identity service third — most dangerous split (cross-cutting auth), requires careful JWT validation migration. The monolith's module boundaries are the service boundaries — no redesign needed, just deploy separately and wire via Kafka/REST.

**Q: Design the click-count-based expiration at 100K RPS on one URL.**

> Naive: `UPDATE url_click_counts SET count = count + 1 WHERE short_code = 'x'` — single row hot lock, throughput limited to ~10K writes/sec. Better: Redis INCR (atomic, in-memory, microseconds). At each redirect: `INCR clicks:x` → if result >= maxClicks → set EXPIRED in Redis + async DB update. Problem: Redis counter is lost on crash. Mitigation: Redis AOF persistence (flush every 1s, lose max 1s of counts). For critical correctness: read Redis counter, flush to DB every 10K increments with CAS (compare-and-swap) to prevent double-counting on replay.

**Q: How do you design for multi-tenancy (white-label short links)?**

> Add `tenant_id` to the data model. Short code uniqueness becomes `(tenant_id, short_code)` composite unique constraint. Short link domain is tenant-specific: `short.acme.com/aB3xYz`. CDN configuration: per-tenant wildcard certificate `*.short.tenantdomain.com` via ACM. Redis namespace: `{tenantId}:url:{shortCode}`. Database: row-level security in PostgreSQL enforces tenant isolation without application-layer filtering (defense in depth). Analytics isolation: ClickHouse tenant partition or per-tenant materialized views. Rate limits: per-tenant quota, not per-user.

---

## "What Would Break First?" Analysis

| Traffic Multiplier | First Bottleneck | Resolution |
|---|---|---|
| 2x | Redis CPU | Local Caffeine cache for hot URLs |
| 5x | Redis memory | Scale Redis cluster memory |
| 10x | Redis network | Redis read replicas |
| 20x | PostgreSQL connections | PgBouncer tuning, add read replicas |
| 50x | Kafka producer throughput | Increase batching, add partitions |
| 100x | Network bandwidth to CDN | Multi-region CDN origin shield |
| 1000x | Global CDN edge compute | Lambda@Edge for zero-origin redirects |

---

## Common Mistakes Candidates Make

| Mistake | What to Say Instead |
|---|---|
| "Just use DynamoDB for everything" | Explain why PostgreSQL for URL uniqueness enforcement; ClickHouse for analytics — different tools for different access patterns |
| "Use MD5 hash of long URL as short code" | MD5 truncation causes collisions; deterministic hash means two people shortening the same URL get the same code (privacy issue) |
| "Store click events in the redirect database" | Immediately spikes redirect latency from 5ms to 50ms+; analytics must be async |
| "301 redirect for better performance" | Loses analytics; can't invalidate cached redirects easily |
| "Pre-shard from day 1" | Premature optimization; single Postgres primary handles 5M creations/day trivially |
| Ignoring the hot URL / hot key problem | Show you understand skewed access patterns; explain Caffeine local cache solution |
| Not mentioning idempotency | Every write API must address "what if the client retries?" — idempotency key is the answer |
| Missing expiration design | Two mechanisms: Redis TTL + background DB job; explain why both are needed |

---

## Senior vs Staff Engineer Expectations

| Dimension | Senior Engineer | Staff Engineer |
|---|---|---|
| Scope | Design one service correctly | Design service ecosystem with clear evolution path |
| Tradeoffs | Explains 2-3 options and picks one | Explains tradeoffs in context of team size, scale, and roadmap |
| Failure modes | Identifies happy path and major failures | Systematic failure analysis across all components |
| Scalability | Knows horizontal scaling | Reasons about bottleneck progression, knows cost implications |
| Security | Mentions auth and input validation | Full threat model, defense in depth, GDPR implications |
| Communication | Answers questions well | Drives the interview, flags implicit requirements, shows leadership |
