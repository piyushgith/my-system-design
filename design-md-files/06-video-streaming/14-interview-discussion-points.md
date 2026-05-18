# 14 — Interview Discussion Points: Video Streaming Platform

## Objective
Consolidate the hardest interviewer follow-up questions, senior vs staff-level discussion depth, common candidate mistakes, and the "what breaks first" analysis for a video streaming system design interview.

---

## Expected Interviewer Questions by Category

### 1. Upload & Ingestion
| Question | Strong Answer Direction |
|----------|------------------------|
| How does a user upload a 10 GB video? | Chunked multipart upload (5–10 MB chunks). Client gets presigned URLs per chunk, uploads in parallel, calls CompleteMultipartUpload. If connection drops, resume from last confirmed chunk using upload manifest in Redis. |
| How do you detect a corrupt upload? | SHA-256 checksum per chunk + whole-file checksum. Client computes and sends, server verifies after assembly. Reject on mismatch, ask client to re-upload bad chunks. |
| What if the upload is aborted halfway? | Periodic cleanup job scans incomplete multipart uploads older than 24h → aborts them on S3. Upload sessions in Redis expire after 24h. |
| How do you handle duplicate uploads? | Content hash (whole-file SHA-256) checked before storage. If hash exists → deduplication, no double storage. Return pointer to existing object. |

### 2. Transcoding Pipeline
| Question | Strong Answer Direction |
|----------|------------------------|
| Walk me through the transcoding pipeline end-to-end. | VideoUploaded event → Kafka → TranscodeWorker picks up → downloads from S3 → FFmpeg (or cloud transcode) → produces 360p/480p/720p/1080p/4K HLS segments → uploads to S3 → publishes TranscodeCompleted → Metadata Service marks video as available. |
| What if a transcode job fails? | Retry with exponential backoff (3 attempts). After 3 failures → DLQ. Alert on-call. Manual re-trigger via admin console. Job is idempotent — safe to retry. |
| How do you scale transcode workers? | KEDA watching Kafka consumer group lag. Lag > 100 → add pods. GPU workers for 4K encoding (g4dn instances on AWS). Separate queues for different quality tiers. |
| How long does 1 hour of 4K video take to transcode? | Real-time ratio for 4K H.265 ≈ 10:1 on a single CPU core. With 16 cores: ~6 minutes. With GPU acceleration: ~2–3 minutes. Parallel quality tier transcoding reduces wall clock further. |
| Why store each quality as separate HLS segments instead of re-encoding on demand? | Pre-transcoding shifts cost from playback time (user-facing latency) to upload time (batch). Re-encoding on demand creates latency spikes and unpredictable CPU costs. |

### 3. Streaming & Delivery
| Question | Strong Answer Direction |
|----------|------------------------|
| Explain adaptive bitrate streaming. | Player starts at lowest quality, requests HLS/DASH manifest listing all quality tiers. Player monitors download speed + buffer health. If buffer filling fast → step up quality. If buffer draining → step down. All segment files are pre-generated on S3. |
| How does CDN edge serving work? | Player requests manifest from CDN URL. CDN checks cache (manifest has short TTL ~5s). On miss → fetches from S3 origin shield. Video segments have long TTL (~1 year, immutable). |
| How do you handle 1 million concurrent viewers on one video? | CDN carries the load — no origin hit for cached segments. CDN fans out to 1M viewers from edge. Only manifest refresh hits origin (once per viewer per ~5s). Origin shield aggregates. |
| What is origin shield and why does it matter? | A single intermediary POP between all CDN edges and S3. Without it, 200 CDN edges × 1M viewers = massive S3 GET cost and latency. With shield, each segment is fetched once from S3, cached at shield, then all edges pull from shield. |
| How do you prevent CDN hotlinking / content theft? | CDN-signed URLs with short TTL (e.g., 6 hours). Token validation on CDN edge. DRM (Widevine for Android/Chrome, FairPlay for iOS/Safari) for premium content. |

### 4. Database & Counting
| Question | Strong Answer Direction |
|----------|------------------------|
| How does YouTube count views accurately at scale? | Approximate counting: Redis HyperLogLog for unique viewers (probabilistic, ~2% error). Exact count via Kafka stream → analytics consumer → batch write to PostgreSQL every 5 min. Show approximate count in UI, exact in creator studio analytics. |
| Why not increment a view counter directly in PostgreSQL per view? | At 100K views/s → 100K write transactions/s on a single row → mutex contention, hot page locking. Database will not survive this pattern. Redis counter → batch flush is correct. |
| How do you partition the views table? | Partition by video_id HASH → spreads writes across partitions. Also consider time-based sub-partitioning for archival. Views older than 90 days → cold storage (S3 Parquet + Athena for analytics queries). |
| How do you handle the N+1 problem in the video feed? | Batch-fetch video metadata for all video IDs in the feed in one query. Use Redis cache for popular video metadata. Never fetch per-video in a loop. |

### 5. Search & Recommendations
| Question | Strong Answer Direction |
|----------|------------------------|
| How does search work at YouTube scale? | Elasticsearch for inverted index on title, description, tags, transcript (auto-generated). Score boosted by view count, recency, user signals. Kafka consumer updates index on video publish. |
| How do recommendations work at a high level? | Two-tower neural network: user embedding + video embedding → similarity score. Candidate generation (ANN search over video embeddings) → ranking model (considers watch history, CTR, session signals). Pre-computed for cold start. |
| How do you handle a new video with no watch history? | Cold start: show in search results, related-video shelf based on metadata similarity (tags, category). After first 1000 views, engagement signals kick in for ML ranking. |

### 6. Scaling & Failures
| Question | Strong Answer Direction |
|----------|------------------------|
| What breaks first at 10× current scale? | Metadata database (read RPS on popular video metadata). Solution: Redis cache in front, read replicas. Second bottleneck: transcode queue depth — add more workers, prioritize tiers. Third: Kafka topic throughput — increase partitions. |
| How do you handle a datacenter failure? | Multi-region active-passive. Route 53 health check fails → DNS TTL (30s) routes to secondary. Aurora Global DB promotes read replica (~1 min). S3 already replicated. RPO: ~1s. RTO: ~5 min. |
| How do you instantly take down a DMCA-reported video? | Soft delete in Metadata Service → mark as UNAVAILABLE → CDN cache invalidation API call (propagates in < 60s). Presigned URL generation blocked immediately after metadata update. Replication lag is the risk — mitigate with CDN purge API. |
| How do you handle a celebrity uploading a viral video that instantly gets 10M views/hour? | CDN handles viewer load (cache hit). The problem is the transcode job queue — celebrity video gets HIGH priority lane in Kafka. Separate topic: `transcode-requests-priority`. Dedicated worker pool processes it first. |

---

## Common Candidate Mistakes

| Mistake | Why It's Wrong | Correct Approach |
|---------|----------------|-----------------|
| Streaming video through app servers | App servers can't handle 60 GB/s egress | S3 + CDN direct delivery |
| Storing video bytes in PostgreSQL | BLOBs in RDBMS don't scale, terrible performance | Object storage (S3) for blobs |
| Single global database for view counts | Hot row contention at 100K writes/s | Redis counter + batch flush |
| On-demand transcoding at play time | User-facing latency spike | Pre-transcode at upload time |
| No origin shield | S3 overwhelmed on viral content | Origin shield between CDN and S3 |
| Recommending same content to all users | Not personalized, poor engagement | User-specific embedding-based retrieval |
| Synchronous transcode in upload API | Upload times out for large files | Async via Kafka, return jobId |

---

## Senior Engineer Discussion Points

- **CAP theorem for video metadata**: Choosing CP — metadata correctness matters (wrong "video not found" is worse than slightly slow). CDN cache is AP (available but eventually consistent after invalidation).
- **Idempotency in uploads**: Same chunk uploaded twice → S3 ETag deduplication + idempotency key in Redis. No double writes.
- **Database schema evolution**: Always expand-contract. Add new column → deploy → backfill → drop old column. Never break the running service.
- **Quota enforcement correctness**: Optimistic locking on storage_used field. Check-then-update with row lock prevents two concurrent uploads both succeeding and overflowing quota.

---

## Staff Engineer Discussion Points

- **Build vs Buy for transcoding**: AWS Elemental MediaConvert (buy) vs self-hosted FFmpeg workers (build). Build: lower cost at scale, full control. Buy: faster time-to-market, AWS SLA, pay-per-minute. At Dropbox scale, build. At startup scale, buy.
- **Encoding format strategy**: H.264 for compatibility (all devices). H.265 for 40% smaller at same quality (but patent royalties). AV1 for open, royalty-free future (Netflix investment). Must transcode to all three at scale.
- **Global content distribution cost**: Egress costs dominate at scale. Negotiate private CDN peering with ISPs (Netflix Open Connect). Cache popular content inside ISP networks to eliminate egress fees entirely.
- **ML pipeline for recommendations**: Feature store (real-time + batch features), two-tower model training pipeline, online serving via FAISS ANN index. A/B testing framework for every model update.
- **Multi-tenancy for creators**: Creator isolation for analytics — row-level security or separate analytics schema per creator tier. Large creators (YouTube Partner Program equivalent) get dedicated analytics pipeline.

---

## "What Would Break First?" Analysis

| Scale Factor | First Bottleneck | Second Bottleneck | Third Bottleneck |
|-------------|-----------------|-------------------|-----------------|
| 2× upload traffic | Transcode queue depth | Upload API pods | Object storage throughput (unlikely) |
| 10× view traffic | CDN cache hit ratio (if content is niche) | Metadata DB read RPS | Redis cluster memory |
| 100× search traffic | Elasticsearch JVM heap pressure | Search index staleness | Query latency P99 |
| Viral video event | Transcode priority queue (one big job) | CDN origin (if not shielded) | Recommendation inference load |
| Creator publishing 1000 videos/hour | Kafka consumer lag on transcode topic | Metadata DB write throughput | Search index update lag |

---

## Tradeoff Discussions Interviewers Expect

| Topic | Option A | Option B | When to Choose |
|-------|----------|----------|----------------|
| HLS vs DASH | Better Apple support | Better DRM flexibility | Both: HLS for iOS, DASH for Android/Web |
| Sync vs async transcode | User waits, sees result immediately | User uploads, video processes in background | Always async at scale |
| Exact vs approximate view counts | Perfect accuracy | 2% error, 100× faster | Approximate for display, exact for payout |
| Pre-generate all qualities vs on-demand | Higher storage cost, instant play | Lower storage, transcoding latency at play | Pre-generate for popular content, lazy for long-tail |
| Push vs pull for recommendations | Pre-computed feed in Redis | Query ML model at request time | Hybrid: pre-compute for top-N, query for rest |
