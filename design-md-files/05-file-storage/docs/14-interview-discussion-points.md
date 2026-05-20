# 14 — Interview Discussion Points: File Storage System

## Objective
Consolidate the hardest interviewer follow-up questions, common mistakes, senior vs staff-level depth, and "what breaks first" analysis for a file storage system design interview. Google Drive and Dropbox are perennial Taking system design questions — interviewers expect deep thinking on deduplication, chunking, sync, and consistency.

---

## Expected Interviewer Questions by Category

### 1. Upload & Chunking

| Question | Strong Answer Direction |
|----------|------------------------|
| How does a user upload a 5 GB file? | Chunked upload: split into 5–10 MB chunks on client. Client computes SHA-256 per chunk + whole-file hash. POST /uploads/init → get presigned S3 URLs per chunk. Upload chunks in parallel directly to S3. POST /uploads/complete with ETags → server calls CompleteMultipartUpload. |
| Why 5–10 MB chunks? | Below 5 MB: too many presigned URLs, too much coordination overhead. Above 10 MB: chunk failure requires retrying too much data. 5–10 MB: S3's recommended multipart part size for optimal throughput. |
| How do you make uploads resumable? | UploadSession in Redis + DB stores which chunk indices have been confirmed (ETags received). On reconnect, client queries upload status → gets list of missing chunks → uploads only those. Client can resume from any chunk. |
| What if the client loses connection during upload? | Session persists in Redis (TTL 24h). Client retries upload, queries status, uploads missing chunks, completes. No re-upload of already-confirmed chunks. |
| How do you prevent the same file from being uploaded twice? | Whole-file SHA-256 hash sent in /uploads/init. If hash matches existing `files.content_hash` → return existing fileId immediately without upload. "Fast path" deduplication. |

### 2. Deduplication

| Question | Strong Answer Direction |
|----------|------------------------|
| How does block-level deduplication work? | Client splits file into fixed-size chunks (5 MB). Computes SHA-256 per chunk. Before uploading: POST /chunks/dedupe-check with chunk hashes. Server checks `chunks` table by hash. If chunk exists → skip upload, reuse. If not → upload. Whole file is assembled from references to existing + new chunks. |
| Isn't client-side hashing a security risk? | Client sends hash, server verifies hash after upload (re-hashes chunk in S3 worker). Deduplication based on wrong hash would only hurt the attacker (they get someone else's data associated with their file, but the data they uploaded is still correct). Server-side hash verification is authoritative. |
| How much storage does deduplication save? | Whole-file deduplication: 20–30% savings (same PDF shared across organization). Block-level (chunk) deduplication: additional 10–15% from overlapping document sections, same header/footer chunks, common binary blocks. At 540 PB/year: 30% savings = 162 PB = ~$3.7M/month at $0.023/GB. |
| How do you handle chunk reference counting? | `chunks.ref_count` increments when a FileVersion references the chunk. Decrements when a FileVersion is deleted. S3 object only physically deleted when ref_count = 0. Race condition prevention: atomic UPDATE + conditional DELETE. |
| Can two users upload the same file and both see it? | Yes. User A and User B each have their own `files` record. Both `current_version`s point to the same `chunk` records. File metadata (name, owner, folder) is per-user. Content (bytes) is shared. |

### 3. Sync Engine

| Question | Strong Answer Direction |
|----------|------------------------|
| How does the sync client know what changed? | Sync Service maintains a change feed per user (ordered event log from Kafka). Sync client stores a cursor (opaque, encodes last processed event offset). On poll: `GET /sync/changes?since={cursor}` → returns delta (list of created/modified/deleted files). Client applies delta locally. |
| How do you handle two devices editing the same file simultaneously? | Conflict detection: Sync Service sees two FILE_MODIFIED events for the same fileId within a short window from different deviceIds. Creates a conflict copy: `filename (conflicted copy from Device B on 2024-01-15).pdf`. Both versions preserved. User notified to manually resolve. |
| Why use polling instead of WebSockets for sync? | 50M DAU × persistent WebSocket = 50M open connections. Stateful, expensive. Polling: stateless, scales horizontally, battery-friendly (desktop client polls every 10s; mobile every 30s). Redis "no changes" check short-circuits 90% of polls at < 1ms — DB never sees them. |
| How does the sync client handle offline mode? | Client stores local copy of sync state (list of files and their version IDs). While offline: tracks local changes in a local queue. On reconnect: upload local changes first, then poll for server changes. Apply server changes that don't conflict with local changes. Conflicts resolved as above. |

### 4. Access Control & Sharing

| Question | Strong Answer Direction |
|----------|------------------------|
| How do you check if a user can download a file? | 1. Validate JWT (stateless). 2. Check file ownership (`files.owner_id == userId` → OWNER, full access). 3. Query Redis permission cache: `perm:{userId}:{fileId}`. 4. If cache miss: query `shares` table for direct share OR inherited share from parent folder. 5. If no access: 404 (not 403 — don't reveal file existence). |
| How do you implement folder-level sharing? | Share record on the folder. Permission check: walk up folder hierarchy until a share is found for the requesting user. Materialize path in `folders.path` enables `WHERE path LIKE '/root/shared-folder%'` ancestor check. Cache result in Redis. |
| How do you instantly revoke a public share link? | On revoke: `shares.is_revoked = true`. Delete Redis permission cache entries for this shareId. CDN cache invalidation for the public share page URL. All subsequent download attempts: permission check fails. CDN propagation: ~60s. |
| What's the latency of a permission check? | Redis hit (permission cache): < 1ms. Cache miss → DB query: ~10ms. DB query goes to read replica (not primary). Cache result for 60s. P99 permission check: < 10ms. |

### 5. Storage & Durability

| Question | Strong Answer Direction |
|----------|------------------------|
| How do you achieve 11 nines of durability? | S3 stores 3 copies across multiple AZs + erasure coding. Cross-Region Replication to secondary region (additional 3 copies). S3 Versioning: deleted objects get delete marker, recoverable. Total: 6 copies across 2 regions + recoverable deletes. |
| How does tiered storage work? | S3 lifecycle policy: move objects to Standard-IA after 30 days of no access (-46% cost), Glacier after 90 days (-83%), Glacier Deep Archive after 1 year (-96%). S3 Intelligent-Tiering automates this based on actual access patterns. |
| If a chunk is deleted prematurely by a GC bug, how do you recover? | S3 Versioning: deletion creates delete marker. Physical bytes still in S3 for 30 days (S3 Glacier has a minimum retention). Remove delete marker → restore chunk. Weekly integrity scan: catches missing chunks before they fall off retention. |
| How do you handle a 1 PB account (large enterprise)? | Quota is enforced. Large enterprise (1 PB quota): their `files` partition (hash by owner_id) is very large. Mitigation: organization-level sharding (enterprise account = dedicated PostgreSQL schema or shard). S3: no per-account size limit. |

---

## Common Candidate Mistakes

| Mistake | Why It's Wrong | Correct Approach |
|---------|----------------|-----------------|
| Storing file bytes in PostgreSQL | BLOB storage kills DB performance, no CDN integration | S3 for bytes, PostgreSQL for metadata |
| Single chunk upload (no multipart) | 5 GB file over one connection fails frequently | Chunked multipart upload with per-chunk retry |
| Checking quota after upload | Wasted storage + bandwidth if quota exceeded | Check + reserve quota before upload begins |
| Client trusts its own file hash | Hash mismatch not caught → corrupted data served | Server re-hashes after upload, verifies |
| ACL check on every byte served (not just URL generation) | S3 serves bytes — no ACL check possible | ACL at presigned URL generation time; signed URLs expire |
| No sync cursor (timestamp-based sync) | Clock skew and replication lag cause missed events | Opaque cursor (Kafka offset or DB sequence) |
| Deduplication without ref counting | Delete one file version → chunk deleted → other users' files corrupted | ref_count ensures physical deletion only when no references remain |
| Same domain for app and user content | XSS: user uploads HTML file that steals app cookies | Separate domain for content serving |

---

## Senior Engineer Discussion Points

- **CAP theorem for file metadata**: CP — consistency is paramount. Cannot show a user a stale file list that shows a deleted file as existing. Accept that during partition, some reads may fail (return error) rather than return stale data.
- **Eventual consistency for search**: Search index lags by seconds. Acceptable — a file you just uploaded not appearing in search for 5 seconds is tolerable. Document this in SLO: "Search results may not reflect changes made in the last 30 seconds."
- **Idempotency everywhere**: Upload complete: idempotent (check if fileVersion with uploadId exists). Chunk deduplication: idempotent (UPSERT with ON CONFLICT). GC decrement: idempotent (track processed versionIds). Every operation must be safely retried.
- **Quota atomicity**: The `UPDATE users SET storage_used_bytes = storage_used_bytes + X WHERE storage_used_bytes + X <= quota_bytes` pattern is the correct atomic quota check-and-deduct. Never read-then-write separately.

---

## Staff Engineer Discussion Points

- **Build vs Buy for object storage**: Google Drive built GFS/Colossus. Dropbox ran on AWS S3 initially, then built their own storage (Magic Pocket) when S3 costs exceeded $40M/year. Decision: at what scale does own storage pay off? Dropbox answer: ~500 PB. Before that: S3 is cheaper (no infra team, no hardware ops).
- **Deduplication cross-user privacy**: Block-level deduplication means two users' files share chunks. Is this a privacy concern? Mitigation: chunks are content-addressed by hash — users never learn that another user has the same chunk. From a legal perspective: the content-addressed approach means you cannot derive what other users' files contain from knowing your own file's chunk hashes (without knowing the whole file).
- **Sync protocol efficiency**: Dropbox Smart Sync: only store metadata locally; download on access. Google Drive: configurable (always sync vs cloud-only). Netflix-style: selective sync (user chooses which folders to sync). Bandwidth savings for users with 100K+ files.
- **GDPR purge at scale**: Purging a user with 1M files requires 1M DB row deletions, 1M+ S3 object deletions, 1M+ Elasticsearch document deletions. At single-threaded delete: 1M × 10ms = 167 minutes. Must parallelize: batch S3 delete (1000 objects/request), parallel DB partition deletes, Elasticsearch bulk delete.

---

## "What Would Break First?" Analysis

| Scale Factor | First Failure | Second Failure | Third Failure |
|-------------|--------------|----------------|---------------|
| 2× upload traffic | Upload Service pods (CPU) | S3 prefix throughput limits | Quota check DB row lock contention |
| 10× metadata reads | Redis cache eviction (memory limit) | PostgreSQL read replica connections | Search indexer lag (Kafka consumer lag) |
| 10× concurrent syncing clients | Sync Service pods | DB query throughput for changes | Redis memory (sync cursors) |
| Viral shared file (1M downloads in 1h) | CDN origin (if not cached) | Download API rate limit | S3 GET throughput per prefix |
| 10× file deletions (mass account cleanup) | GC DLQ depth (rate limited to 10K/hr) | ref_count update DB throughput | S3 delete batch capacity |
| GDPR mass purge request | Purge pipeline queue depth | S3 delete throughput per account | Search bulk delete cluster load |

---

## Tradeoff Discussions Interviewers Expect

| Topic | Option A | Option B | Production Choice |
|-------|----------|----------|------------------|
| Sync: polling vs WebSocket | Stateless, cheap, battery-friendly | Real-time push, complex | Polling (with Redis short-circuit for efficiency) |
| Deduplication: whole-file vs block-level | Simple, fast | More savings, complex | Whole-file at MVP; block-level at scale |
| DB: PostgreSQL vs Cassandra | ACID, complex queries, vertical scaling | High write scale, denormalized | PostgreSQL (file metadata needs JOINs, ACID) |
| S3 hosting: AWS vs own datacenter | Managed, higher unit cost | Lower unit cost, huge infra investment | AWS until ~500 PB (Dropbox inflection point) |
| Versioning: unlimited vs capped | Better UX, higher storage | Lower storage, predictable quota | Capped by tier (free: 30 versions; pro: unlimited) |
| Search: Elasticsearch vs PostgreSQL FTS | Rich relevance, scalable | Simple, no extra infra | ES (required for large-scale relevance + facets) |
