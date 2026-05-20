# 14 — Interview Discussion Points: Pastebin / Code Sharing Platform

---

## Objective

Prepare for every angle a Taking interviewer might probe. Cover foundational questions, tradeoff deep-dives, scaling evolution, failure analysis, and staff-level architectural thinking.

---

## Opening Questions (First 5 Minutes)

These are asked to calibrate your level. Answer concisely and then offer to dive deeper.

**Q: Walk me through your high-level design for Pastebin.**

> Key components to mention: API layer, application server, PostgreSQL for metadata, S3 for content, Redis for caching, CDN for read scaling, Kafka for async processing. State the read:write ratio (10:1) and the immutable content property upfront — these drive most decisions.

**Q: What are the core APIs you'd design?**

> POST /pastes (create), GET /pastes/{key} (read), GET /raw/{key} (raw content), DELETE /pastes/{key} (delete). Mention: pagination for user paste list (cursor-based), idempotency key for create.

**Q: What's the single most important design decision here?**

> Where to store paste content. Answer: Object storage (S3), not the database. At 10 KB average paste size and 10M pastes/month, that's 100 GB/month. Storing blobs in PostgreSQL causes table bloat, slow vacuums, and expensive full-table operations. S3 is infinitely scalable and orders of magnitude cheaper per GB.

---

## Deeper System Design Questions

### Short Key Generation

**Q: How do you generate unique short keys? What are the collision risks?**

> Two strategies: random Base62 with collision retry, or counter-based Base62 (no collisions possible).
>
> Random approach: 62^6 = 56 billion keys. At 10M active pastes, collision probability per key attempt is ~0.018% — acceptable with retry.
>
> Counter-based: increment a global counter (PostgreSQL SEQUENCE or Redis INCR), encode as Base62. Guaranteed unique. Downside: reveals paste volume and ordering. Mitigate with large random offset and bit-shuffling.
>
> Recommendation: Counter-based for MVP; Snowflake ID for distributed generation at scale.

**Q: How would a collision look from the user's perspective, and how do you handle it?**

> Retry transparently — user never sees the collision. The application generates a new key and retries the insert. With a unique DB constraint, the insert fails fast on collision, and a new key is generated. Max retry: 3 attempts with increasing key length (6 → 7 → 8 chars).

---

### Content Storage

**Q: Why S3 and not PostgreSQL TEXT column for paste content?**

> PostgreSQL TOAST handles large text per row, but: (1) bloats table pages → slower full-table operations (vacuum, backup, EXPLAIN), (2) TOAST accessed via separate page reads = higher I/O, (3) can't independently scale storage from compute, (4) S3 is 23× cheaper per GB than PostgreSQL storage (EBS).
>
> Exception: inline small content (<1 KB) in DB avoids S3 round-trip for tiny pastes and is a common optimization.

**Q: What is content deduplication and how does it work here?**

> Two pastes with identical content share one S3 object. On creation: compute SHA-256 of content, check if existing paste has same hash, if yes → reuse the same `s3Key`. This saves storage for duplicate pastes (e.g., the same config file shared by many developers). 
>
> Tradeoff: if one paste is deleted, the S3 object cannot be deleted until all pastes with the same hash are deleted. Track reference count or check before deleting.

**Q: What's your threshold for inline vs S3 storage?**

> < 1 KB inline in DB: avoids S3 latency for tiny pastes (fastest path for most code snippets). ≥ 1 KB in S3: avoids DB bloat. The boundary is configurable.

---

### Expiration and Cleanup

**Q: How do you handle paste expiration at scale (6M expirations/month)?**

> Two-step process: (1) On paste creation, insert into `expiry_schedule(paste_id, expires_at)`. (2) Scheduled job polls every 60 seconds: `SELECT ... WHERE expires_at <= NOW() AND processed = FALSE ... FOR UPDATE SKIP LOCKED`. Publishes `paste.expired` events to Kafka. Cleanup consumers process deletions (DB soft-delete + S3 delete + CDN invalidation).
>
> `FOR UPDATE SKIP LOCKED` is critical — allows multiple cleanup instances without double-processing the same paste.

**Q: Why not use Redis TTL for expiration?**

> Redis TTL eviction is approximate, not guaranteed. On Redis restart, TTL data is lost. Not suitable as the sole expiration mechanism — use as a secondary hint only. PostgreSQL-based scheduling is durable.

**Q: What happens if a paste's CDN cache hasn't expired when the paste is deleted?**

> CDN invalidation is triggered asynchronously (paste.deleted event → CDN Invalidator consumer → CloudFront CreateInvalidation API). CloudFront propagates invalidation in 5-30 seconds. During this window, stale content may be served. For most use cases, this is acceptable. If zero-stale-content is required: set shorter CDN TTLs and accept slightly lower cache hit ratio.

---

### Caching

**Q: What is a cache stampede and how do you prevent it in Pastebin?**

> Cache stampede (thundering herd): when a popular cache entry expires, hundreds of concurrent requests all miss the cache simultaneously and hammer the DB.
>
> Prevention: Redis mutex pattern. First requester acquires a lock (`SET lock:{key} 1 NX EX 5`). All others wait 100ms and re-check. First requester populates cache on DB fetch. When lock released, all waiters are served from cache.

**Q: What is negative caching? Would you use it here?**

> Negative caching: storing the "not found" result in cache to prevent repeated DB queries for missing keys. For Pastebin: `paste:notfound:{key}` with 60-second TTL. Prevents attackers from probing non-existent keys and hammering the DB.

**Q: Should you cache private pastes?**

> Not in CDN (CDN cannot differentiate per-user content). Can cache in Redis with the paste as a private entry — but only if the access control check runs before the cache lookup. The correct pattern: check access level first (from cached metadata), then serve content. Never return private content without auth check, even from cache.

---

### Access Control and Security

**Q: How does access control work for the 3 paste types?**

> PUBLIC: no auth required, CDN-cacheable. UNLISTED: no auth required but not indexed/listed — only accessible with exact URL (security by obscurity). PRIVATE: requires JWT authentication, owner check enforced server-side.
>
> A password-protected paste adds an additional layer on top of the access level. Access decision: deleted check → expired check → access level check → password check.

**Q: How do you prevent brute-force enumeration of short keys?**

> Rate limiting: 100 GET requests per minute per IP. After 3 consecutive 404s from same IP, increase to CAPTCHA challenge. For UNLISTED pastes: by obscurity (62^6 space is too large to enumerate practically at 100 req/min). For PRIVATE: auth required, only owner can access regardless of knowing the key.

**Q: Why store paste passwords with bcrypt instead of SHA-256?**

> Paste passwords are user-chosen and likely weak ("hello", "password123"). SHA-256 is fast → rainbow tables or brute-force is practical. Bcrypt is designed to be slow (configurable cost factor) → brute-force is impractical. Same argument as for user account passwords.

---

### Read Scaling

**Q: Walk me through the read path for a public paste from someone in Tokyo.**

> 1. Browser requests `https://pastebin.io/raw/abc123`. DNS resolves to nearest CloudFront edge (Tokyo PoP). 2. CDN checks cache — HIT → serve directly (~5ms). MISS → forward to origin (us-east-1). 3. Origin checks Redis — HIT → serve from Redis, warm CDN. MISS → query PostgreSQL → fetch S3 → warm Redis → warm CDN → serve. 4. Subsequent Tokyo requests served from CDN edge (~5ms).

**Q: What happens when a paste goes viral immediately after creation (CDN is cold)?**

> Thundering herd risk. Mitigations: (1) Pre-warm CDN after creation (synthetic request to `/raw/{key}`). (2) Redis mutex on cache miss prevents DB stampede. (3) Pre-signed S3 URLs allow clients to fetch content directly from S3 (bypass origin for content). (4) Local in-process Caffeine cache (L0) for extreme hot keys.

---

### Data Modeling

**Q: What does an anonymous paste's data model look like (no user ID)?**

> `pastes.owner_id = NULL`. Anonymous pastes are public or unlisted (cannot be private — no owner to auth against). Cleanup is time-based only (no owner to notify). Anonymous paste can be managed only by whoever holds the "owner token" returned at creation (optional pattern: return a one-time deletion token for anonymous users).

**Q: How would you add versioning to pastes without rewriting the schema?**

> Add `paste_versions` table with `(id, paste_id, version_number, content_type, content_inline, content_s3_key, created_at)`. Current paste points to version N. Creating a new version: (1) insert current content into paste_versions as version N, (2) update pastes with new content as version N+1. Immutable versions in S3 with versioned S3 keys.

---

## Staff/Principal Engineer Discussion Points

**Q: How would you evolve this from a modular monolith to microservices?**

> Use Strangler Fig pattern. Extract services one at a time: (1) Extract Cleanup Service first (isolated, event-driven, no sync calls back to monolith). (2) Extract Analytics Service (own DB — ClickHouse or TimescaleDB, consumes Kafka events). (3) Extract Moderation Service (Python for ML integration). (4) Extract Paste Delivery Service (stateless, CDN-optimized, read-only). Monolith remains the write path until confidence is high.

**Q: What would break first at 10x current scale?**

> Most likely: PostgreSQL write throughput for the `expiry_schedule` table (cleanup job doing 60K INSERTs per hour). Then: Redis memory for hot pastes. Then: S3 upload bandwidth on the application server (resolved with pre-signed client-direct upload). PostgreSQL can handle 10x current paste traffic with proper indexing and connection pooling (PgBouncer).

**Q: How would you design multi-region Pastebin?**

> Writes: route to home region (us-east-1 master). Reads: route to nearest region with read replica. S3 Cross-Region Replication for content availability. PostgreSQL streaming replication across regions (50-200ms lag — acceptable for eventual consistency). CDN handles most global read traffic without region awareness.

**Q: What are the ethical considerations in Pastebin design?**

> (1) Abuse prevention: malware, phishing, CSAM. Cannot allow anonymous creation without any friction. (2) Privacy: paste content is sensitive — don't log it, encrypt at rest, isolate on separate S3 domain. (3) DMCA compliance: must have takedown process. (4) Data retention: NEVER expiry pastes could store data indefinitely — apply storage limits. (5) Law enforcement: private pastes may be subject to lawful access requests — need a clear policy.

---

## Common Mistakes in Pastebin Interviews

| Mistake | Better Answer |
|---------|--------------|
| Storing content in PostgreSQL TEXT | Use S3 for content ≥ 1 KB |
| Offset-based pagination for user paste list | Cursor-based (stable across mutations) |
| Updating view count on every read | Async batch update via Kafka |
| Returning 404 for expired pastes | Return 410 Gone (semantically distinct) |
| No negative caching for missing keys | Cache "not found" with short TTL |
| Single cleanup instance | Multiple instances with SKIP LOCKED |
| Storing passwords as SHA-256 | Use bcrypt (slow hash) |
| CDN caches private pastes | Private pastes must bypass CDN |
| Blocking write path for all side effects | Async via Kafka (outbox pattern) |
| Forgetting CDN invalidation on delete | Invalidation is critical for correctness |

---

## "What Would Break First?" Analysis

```
Bottleneck order (from first to last to break):
1. S3 upload latency       → Large paste creation slow
   Fix: Pre-signed client-direct upload

2. Redis memory            → Hot pastes evicted → DB hit increase
   Fix: Increase Redis size, eviction policy tuning

3. PostgreSQL connections  → Connection pool exhaustion
   Fix: PgBouncer connection pooler

4. PostgreSQL write IOPS   → Expiry schedule inserts, view count batch updates
   Fix: SSD-backed RDS, write batching

5. PostgreSQL read IOPS    → Cache miss load on primary
   Fix: Read replicas (already in T2 plan)

6. CDN cost               → Invalidation cost at high deletion volume
   Fix: Shorter TTLs, batch invalidations

7. Kafka consumer lag      → Analytics stale, cleanup delayed
   Fix: Scale consumer instances
```

---

## Questions to Ask the Interviewer

A great candidate also asks good questions:

1. "What is the expected ratio of public vs private pastes? This affects CDN hit ratio assumptions."
2. "Is content moderation a hard requirement from day one, or can it be phase 2?"
3. "What's the target SLA for paste reads — 99.9% or 99.99%? That changes the infrastructure cost significantly."
4. "Are there compliance requirements (GDPR, CCPA) for user data? Affects data retention strategy."
5. "How large is the engineering team? That affects how much we'd benefit from microservices vs monolith."
