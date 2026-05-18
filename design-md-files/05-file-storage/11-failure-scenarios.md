# 11 — Failure Scenarios: File Storage System

## Objective
Enumerate real production failure modes for a file storage platform, define their blast radius, detection mechanism, recovery procedure, and preventive measures. File storage systems are particularly sensitive to failures because data loss or corruption is unacceptable — users trust us with irreplaceable documents.

---

## Failure Classification

| Severity | Description | Examples |
|----------|-------------|---------|
| **P0 — Data Loss Risk** | Any failure that could cause permanent data loss | S3 bucket deleted, DB corruption, GC bug |
| **P1 — Service Outage** | Core functionality unavailable for users | Upload API down, download failing |
| **P2 — Degraded Service** | Reduced quality or partial feature failure | Search stale, previews not generating |
| **P3 — Minor Degradation** | Noticeable but not blocking | Slow folder listing, delayed notifications |

---

## Failure Scenario 1: S3 Object Storage Outage

### Description
S3 region or prefix experiences downtime. Upload and download of file bytes is unavailable.

### Blast Radius
- Upload: all new uploads fail (file API returns 503).
- Download: all downloads fail. If CDN edge has cached the file → CDN serves it. If not cached → 503.
- Metadata reads: unaffected (no S3 dependency).
- **CDN buffer**: files that were recently downloaded by anyone will be cached at edge. Most popular files: available from CDN for cache TTL (up to 1 year for chunks).

### Detection
- CloudWatch alarm: `S3 PutObject error rate > 1%` for 2 minutes → PagerDuty CRITICAL.
- Upload Service health check: calls `S3 HeadBucket` every 30 seconds.
- CDN origin error rate spike.

### Recovery Procedure
1. Confirm S3 regional outage via AWS Status page.
2. Activate "maintenance mode" banner in UI: "File uploads temporarily unavailable."
3. For downloads: check CDN hit ratio. If low → S3 Glacier expedited retrieval for critical files (manually triggered by ops).
4. On S3 recovery: flush any stuck upload sessions from Redis → prompt users to retry.
5. No data loss — uploads were rejected, not corrupted.

### Prevention
- S3 Cross-Region Replication: all uploaded chunks replicated to secondary region within minutes.
- For download: CDN origin shield with fallback origin (secondary region S3 bucket).
- Presigned URL generation can temporarily point to secondary region bucket.

---

## Failure Scenario 2: Upload Service Crash Mid-Upload

### Description
Upload Service pod crashes after a client uploads some chunks but before `CompleteMultipartUpload` is called.

### Blast Radius
- In-progress upload for that user stalls. Upload session in `IN_PROGRESS` state indefinitely.
- Uploaded chunks are orphaned in S3 (incomplete multipart upload).
- Other users: unaffected (stateless service, other pods take traffic).

### Detection
- Client-side: upload API returns connection reset → client retries with same `uploadId`.
- If pod restarted: new pod has no in-memory state — but upload session is in Redis + DB.
- User: "resume upload" flow kicks in (client queries upload status API).

### Recovery
1. Client sends `POST /uploads/{uploadId}/status` → gets chunk manifest from DB.
2. Client re-uploads only missing chunks (manifest shows which ETags were confirmed).
3. Client calls `POST /uploads/{uploadId}/complete` again → idempotent.

### Prevention
- Upload session state in Redis + PostgreSQL (not in-process). Pod crash loses nothing.
- Client implements exponential backoff retry with jitter.
- Incomplete multipart uploads in S3: lifecycle policy aborts after 24 hours.
- `uploadId` is idempotent — calling complete multiple times returns the same result.

---

## Failure Scenario 3: PostgreSQL Primary Failure

### Description
Primary database instance fails (hardware fault, software crash, network partition).

### Blast Radius
- All writes fail: new uploads, file creation, permission changes, quota updates.
- Reads from replica: unaffected — folder browsing, file metadata reads continue.
- Aurora Multi-AZ: automatic failover to standby replica in < 30 seconds.
- Application: connection errors during failover window (30–60 seconds).

### Detection
- Aurora failover notification → CloudWatch alarm → PagerDuty.
- Application: connection pool errors → circuit breaker opens.
- Health check endpoint: `GET /health` → reports DB connection state.

### Recovery (Automatic — Aurora Multi-AZ)
1. Aurora detects primary failure.
2. Promotes standby replica to primary (< 30 seconds).
3. DNS endpoint (`cluster.aurora.endpoint`) automatically points to new primary.
4. Application reconnects (via connection pool retry + exponential backoff).
5. Read replicas are now lagging — temporarily serve slightly stale reads.
6. On recovery: read replicas catch up (minutes).

### Manual Recovery (If Automation Fails)
1. Verify new primary via `SHOW primary_host;` in PostgreSQL session.
2. Update application DB URL if DNS not auto-updated (rare with Aurora).
3. Verify write operations succeed on new primary.
4. Monitor replica lag.

### Prevention
- Aurora Multi-AZ always on.
- Connection pooling (PgBouncer or HikariCP) with retry configuration.
- Circuit breaker (Resilience4j): on DB unavailable → return cached responses, queue writes.
- RPO: 0 (synchronous replication to standby). RTO: < 60 seconds.

---

## Failure Scenario 4: Kafka Cluster Degradation

### Description
Kafka broker failures or partition leader elections cause consumer lag or message delivery failures.

### Blast Radius
- Upload completion events not reaching Metadata Service → files stuck in `PROCESSING` state.
- Search index falls behind → stale search results.
- Sync clients don't get changes → sync is delayed.
- Preview generation queued.
- No data loss — events buffered in Kafka (14-day retention).

### Detection
- Kafka consumer group lag metric (JMX → Prometheus → Grafana).
- Alert: `kafka_consumer_group_lag > 10,000` for any consumer group.
- Upload API: response time increases as Metadata Service backs up.

### Recovery
1. Identify failing broker via Kafka admin tools.
2. If broker crashed: restart Kafka broker. Partition leaders re-elected automatically.
3. Consumer lag will recover as broker comes back online — consumers replay from committed offsets.
4. If partition leader unavailable: reassign partition leadership via Kafka admin.
5. No message re-publishing needed — events are durable in Kafka log.

### For Users
- Files may stay in `PROCESSING` state during extended Kafka outage (> 5 minutes).
- Implement polling fallback: if file stays in PROCESSING > 5 minutes → Upload Service queries Upload Session DB directly and creates FileVersion record synchronously.
- This fallback is rate-limited (not available for all files — only for recent uploads).

### Prevention
- MSK (Managed Kafka): AWS handles broker health.
- `replication.factor=3, min.insync.replicas=2`: survives 1 broker failure.
- `unclean.leader.election.enable=false`: no data loss on leader election.

---

## Failure Scenario 5: GC Bug — Premature Chunk Deletion

### Description
A bug in the Storage GC Job incorrectly decrements `ref_count` to 0 for a chunk that is still referenced by active FileVersions → S3 deletion of the chunk → data loss.

### Blast Radius
- Files that reference the deleted chunk → corrupted (missing chunks).
- Unrecoverable if S3 Versioning is not enabled.
- P0 — data loss incident.

### Detection
- S3 Versioning: deleted chunks are soft-deleted (delete marker). Can be restored.
- Integrity check job: weekly scan validates that all chunks referenced by `file_version_chunks` exist in S3. Alert on missing chunks.
- Application: download request for corrupted file → S3 returns 404 → error logged.

### Recovery
1. Stop GC Job immediately.
2. Restore deleted S3 objects from S3 Versioning (remove delete markers).
3. Recalculate `ref_count` from scratch: `SELECT chunk_id, COUNT(*) FROM file_version_chunks GROUP BY chunk_id` → update `chunks.ref_count`.
4. Root cause GC bug → fix + regression test.
5. User-facing: affected files show "Restore error — contact support."

### Prevention
- **S3 Versioning**: always enabled. Deletion creates delete marker, not permanent removal.
- **S3 Object Lock (WORM)**: for archive/compliance use case — objects cannot be deleted during lock period.
- **GC dry-run mode**: GC job has a `DRY_RUN=true` flag. Always validate in staging with dry-run first.
- **GC soft-delete**: instead of deleting S3 objects directly, move to `s3://bucket-name/quarantine/` prefix for 48 hours before permanent deletion. Recovery window.
- **Chunk deletion rate limit**: GC deletes at most 10,000 chunks/hour. Limits blast radius of a bug.

---

## Failure Scenario 6: Redis Cluster Failure

### Description
Redis cluster (6 shards) experiences failure — one or more shards go down.

### Blast Radius
- Upload session state: **critical** — upload session lost if shard containing session goes down. In-progress uploads fail.
- Permission cache: fails → fallback to DB (slower, but correct).
- File metadata cache: fails → fallback to DB (slower, but correct).
- Sync check cache: fails → all poll requests hit DB. 50M DAU polling = 5M DB queries/minute.

### Tiered Blast Radius
| Redis Data | On Failure | Impact |
|------------|------------|--------|
| Upload sessions | Lost — user must restart upload | P1 for uploading users |
| Permission cache | Fallback to DB | P2 — latency increase |
| Metadata cache | Fallback to DB | P2 — latency increase |
| Quota cache | Fallback to DB | P3 — slightly slower |
| Sync check | Fallback to DB | P1 — 5M RPS to DB → DB overload |

### Recovery Priority
1. Immediately: enable circuit breaker on Sync Service → rate limit poll requests to 1 request/30s per user (10× normal interval).
2. Spin up replacement Redis shard (ElastiCache auto-replacement: 60-120 seconds).
3. Repopulate caches from DB (warm-up job for top 10M most-accessed files).
4. Re-enable sync poll to normal frequency.

### Prevention
- 6 shards × 1 replica per shard: losing one shard → replica promoted automatically by ElastiCache.
- Upload session durability: write upload session to BOTH Redis AND PostgreSQL. On Redis miss → read from PostgreSQL. Slightly slower but never loses upload state.
- Sync Service circuit breaker: rate limit DB fallback to prevent cascade.

---

## Failure Scenario 7: Quota Enforcement Race Condition

### Description
Two concurrent uploads by the same user, both checking quota simultaneously. Both see "10 GB used, 15 GB quota, 4 GB remaining." Both upload 4 GB files. Both succeed. User is now at 18 GB (over quota by 3 GB).

### Root Cause
Read-then-write without atomicity: `SELECT storage_used, quota` → check → `UPDATE storage_used + 4GB`. Between the two operations, the second concurrent upload runs the same sequence.

### Blast Radius
- User exceeds quota by up to N × file_size (where N = concurrent uploads).
- Storage cost borne by platform.
- Not catastrophic — correctness issue, not data loss.

### Recovery
- Detect via reconciliation job: `SELECT user_id FROM storage_quota_events GROUP BY user_id HAVING SUM(delta) > quota_bytes`.
- Alert user: "Your storage is over limit. Please delete files or upgrade."
- Block future uploads until resolved.
- Do NOT delete files automatically — user data deletion requires explicit user action.

### Prevention
**Correct implementation**:
```sql
-- Atomic quota check-and-deduct
UPDATE users 
SET storage_used_bytes = storage_used_bytes + :newFileSize
WHERE user_id = :userId 
  AND storage_used_bytes + :newFileSize <= storage_quota_bytes
RETURNING storage_used_bytes;

-- If 0 rows updated → quota exceeded → reject upload
```

`SELECT FOR UPDATE` + `UPDATE` in same transaction with row-level lock. No two concurrent uploads can both pass the check for the same user.

---

## Failure Scenario 8: GDPR Purge Incomplete

### Description
User requests account deletion. GDPR purge pipeline is triggered. Pipeline crashes mid-way — some files deleted from DB, some still in S3.

### Blast Radius
- Regulatory violation: GDPR requires complete deletion within 30 days.
- Some user data remains on platform after deletion.

### Recovery
- GDPR purge is idempotent: re-running the purge for `userId` deletes all remaining artifacts.
- `gdpr_deletion_jobs` table tracks progress (per-artifact: PENDING / COMPLETE / FAILED).
- Daily job: find all `gdpr_deletion_jobs` with status FAILED older than 1 day → re-trigger.
- Manual audit: compliance team verifies no data remains for deleted user after 30 days.

### Prevention
- Saga-like purge pipeline: each step (delete DB records, delete S3 objects, purge search index, purge Kafka events, revoke shares) is a separate idempotent job.
- Each step tracked in `gdpr_deletion_jobs` with status.
- Retry indefinitely (with exponential backoff) until all steps COMPLETE.
- Alert if any step fails > 5 times → escalate to compliance team.

---

## Failure Scenario Summary Table

| Scenario | Severity | RTO | RPO | Auto-Recovery |
|----------|----------|-----|-----|--------------|
| S3 regional outage | P1 | 30 min (CDN covers reads) | 0 (uploads rejected, not lost) | Partial (CDN) |
| Upload Service pod crash | P2 | 30 seconds | 0 | Yes (client retry) |
| PostgreSQL primary failure | P1 | 60 seconds | 0 | Yes (Aurora Multi-AZ) |
| Kafka cluster degradation | P2 | 5–30 min | 0 | Mostly (consumer replay) |
| GC bug — chunk deletion | P0 | 2–4 hours | Depends on S3 Versioning | No (manual) |
| Redis cluster failure | P1–P2 | 2–5 min | Upload sessions lost | Partial |
| Quota race condition | P3 | Batch reconciliation | — | No (detect + notify) |
| GDPR purge incomplete | P1 | Re-run within 24h | — | Partial (retry job) |

---

## Interview-Level Discussion Points

- **What's your approach to preventing data loss?** — Defense in depth: S3 Versioning (recoverability), GC soft-delete quarantine (48h recovery window), weekly integrity scan (detect issues before they compound), chunk rate limiting (limit blast radius of bugs).
- **How do you handle the Redis failure → sync DB overload cascade?** — Circuit breaker on Sync Service: immediately rate-limit poll frequency from 1/10s to 1/30s. This reduces DB load by 3×. Simultaneously, alert on-call to restore Redis. Without this circuit breaker, Redis failure → DB overload → DB failure → full outage.
- **Why not use 2PC for quota enforcement?** — 2PC requires a coordinator and all participants to be available. Under partial failure, the coordinator can leave transactions in doubt indefinitely. PostgreSQL single-node ACID (`UPDATE ... WHERE ... AND quota_check` in one statement) gives us atomic check-and-deduct without distributed coordination. Single-node atomicity is the correct tool here.
- **How do you test failure scenarios in production?** — Chaos engineering: Netflix Chaos Monkey approach. Randomly terminate pods in non-peak hours. Inject Redis connection failures in staging. Simulate S3 throttling. Verify that circuit breakers, retries, and fallbacks work as designed. Game Day exercises: simulate full region failure with on-call team.
