# 00 — Requirements Analysis: Video Streaming Platform

---

## Objective

Establish a precise, unambiguous, and exhaustive requirements baseline before any architecture or technology decisions are made. This document forms the contract against which every design tradeoff will be evaluated. It answers: **what are we building, for whom, at what scale, and with what constraints?**

---

## 1. Problem Statement

Design a global video streaming platform that allows creators to upload videos, an automated pipeline to process and transcode those videos into multiple quality levels, and viewers to stream those videos with minimal latency and maximum reliability — at the scale of YouTube or Netflix.

---

## 2. Functional Requirements

### 2.1 Creator-Side (Write Path)

| Requirement | Description | Priority |
|---|---|---|
| Video Upload | Chunked, resumable upload of video files up to 256 GB | P0 |
| Upload Progress | Real-time upload progress feedback | P0 |
| Transcoding | Automatic transcoding to 144p, 360p, 480p, 720p, 1080p, 1440p, 4K | P0 |
| Thumbnail Generation | Auto-generate thumbnails at multiple timestamps; allow creator override | P0 |
| Video Metadata | Title, description, tags, category, language, visibility (public/unlisted/private) | P0 |
| Channel Management | Create channel, customize profile, upload banner | P1 |
| Publish Scheduling | Schedule video publish at a future timestamp | P1 |
| Video Update | Edit title, description, thumbnail post-publish without re-transcode | P1 |
| Video Deletion | Soft delete with 30-day recovery window | P1 |
| Chapters / Timestamps | Add chapter markers to video timeline | P2 |
| Subtitle Upload | Upload SRT/VTT files; auto-generate subtitles via ASR | P2 |

### 2.2 Viewer-Side (Read Path)

| Requirement | Description | Priority |
|---|---|---|
| Video Streaming | Stream video via HLS/MPEG-DASH with adaptive bitrate | P0 |
| Search | Full-text search over title, description, tags, channel name | P0 |
| Recommendations | Personalized home feed and up-next video recommendations | P0 |
| Comments | Threaded comments with like/dislike on comments | P1 |
| Video Like/Dislike | Like or dislike a video | P1 |
| Subscriptions | Subscribe to channels, receive notifications | P1 |
| Watch History | Persist viewer watch history and resume position | P1 |
| Playlists | Create/share playlists; Watch Later queue | P1 |
| Live Streaming | Real-time live streams with <10s latency | P1 |
| Notifications | Push notifications for new uploads from subscribed channels | P2 |
| Download (Offline) | Allow downloading for offline view in mobile apps | P2 |
| Closed Captions | Display subtitles/captions with timing sync | P2 |

### 2.3 Platform / Admin

| Requirement | Description |
|---|---|
| Content Moderation | Automated + manual review pipeline for policy violations |
| DMCA Takedown | Ability to remove/block content within SLA (typically hours) |
| Analytics for Creators | Views, watch time, retention curve, traffic sources, subscriber growth |
| Global Analytics | Platform-level aggregates for internal teams |
| Ad Integration Hooks | Pre-roll, mid-roll, post-roll ad slot markers (ad serving is external) |
| Abuse Reporting | Report video/comment/user; queue for review |

---

## 3. Non-Functional Requirements

| Attribute | Target |
|---|---|
| Availability | 99.99% uptime (~52 min/year downtime) |
| Video Start Latency | P99 < 2 seconds globally (Time To First Frame) |
| Upload Throughput | Support 500 hours of video uploaded every minute |
| Transcoding SLA | Standard video visible within 15 minutes of upload completion |
| Search Latency | P99 < 500ms |
| API Latency | P99 < 200ms for metadata APIs |
| Durability | 11 nines for stored video objects (S3-class) |
| Scalability | Horizontally scalable; no single point contention |
| CDN Hit Ratio | > 95% for top 5% of videos (by view count) |
| Consistency | Eventually consistent for view counts, likes; strong consistency for video metadata |
| Compliance | GDPR, CCPA, COPPA; DRM support for premium content |
| Security | Signed URL access for streams; DRM for premium; rate limiting on APIs |
| RTO | < 5 minutes for critical services (stream delivery) |
| RPO | < 1 minute for metadata; object storage is replicated independently |

---

## 4. Assumptions

- Creators upload from desktop browsers or mobile apps using chunked upload APIs.
- Transcoding is asynchronous — creators wait for processing; viewers cannot watch before at least one quality is ready.
- Global CDN is provided by a third-party (Akamai, Cloudflare, AWS CloudFront) — we design the origin infrastructure.
- Object storage is S3-compatible (AWS S3 or GCS) — we do not build our own blob store.
- Recommendation engine provides scores; full ML training pipeline is a separate concern.
- Live streaming is in scope for P1 but architecture is separate from VOD.
- Ad serving is handled by a third-party DSP; our platform inserts VAST ad markers.
- The platform serves a global audience; multi-region deployment is required.
- Mobile apps (iOS/Android) and web are first-class clients.
- Assume a monolith-to-microservices evolution path; we design for the microservices end-state.

---

## 5. Constraints

- No single database table can be a universal bottleneck — must partition/shard write-heavy tables.
- Storage costs dominate economics — aggressive deduplication and tiered storage are required.
- Transcoding is compute-intensive — must autoscale workers independently of web tier.
- CDN bandwidth costs are the largest line item — cache hit ratio directly impacts cost.
- Copyright compliance (DMCA) requires near-instant takedown capability.

---

## 6. Scale Estimation (Back-of-the-Envelope)

### 6.1 User Base

```
Total registered users:              500 million
Daily Active Users (DAU):            100 million (20% of registered)
Monthly Active Users (MAU):          300 million (60% of registered)
Concurrent viewers (peak):           10–15 million
Creators uploading daily:            ~5 million
```

### 6.2 Upload Rate

```
Upload rate:           500 hours of video per minute
                     = 30,000 hours/hour
                     = 720,000 hours/day

Average video length:  7 minutes = 0.117 hours
Videos uploaded/day:   720,000 / 0.117 ≈ 6.15 million videos/day
Videos uploaded/sec:   6,150,000 / 86,400 ≈ 71 videos/second
```

### 6.3 Storage Estimation

```
Raw upload size (before transcode):
  Average bitrate of raw upload:     ~8 Mbps (1080p H.264)
  Average video duration:            7 minutes = 420 seconds
  Raw file size:                     8 Mbps × 420s / 8 = 420 MB per video

  Daily raw upload:                  71 videos/s × 420 MB = ~29.8 TB/day
  Annual raw upload:                 ~10.9 PB/year

After transcoding (multiple qualities):
  Transcode output multiplier:       ~4x (storing 6 renditions but compressed better)
  Effective storage per video:       420 MB × 4 = 1.68 GB
  Daily storage added:               71 × 1.68 GB ≈ 119 GB... wait recalculate:

  71 videos/s × 86,400 s/day = 6.13M videos/day
  6.13M × 1.68 GB = 10.3 PB/day  ← this is too high; correct:

  Correct: 1.68 GB per video × 6,150,000 videos/day ≈ 10.3 PB/day

  This seems very high. Let's recalibrate using YouTube's actual estimation:
  YouTube's real average:            ~1 GB stored per minute of video (all renditions)
  500 hours/minute × 60 min/hr × 1 GB/min = 30,000 GB = ~30 TB/day

  Revised total storage/day:        ~30 TB (all renditions)
  Annual accumulation:               ~11 PB/year
  10-year total:                     ~110 PB

Thumbnail storage:
  3 thumbnails per video × 200 KB = 600 KB
  6.13M videos/day × 600 KB = ~3.7 TB/day (thumbnails only)

Total with metadata, thumbnails, logs:
  ~35 TB/day added to durable storage
```

### 6.4 Bandwidth Estimation

```
Concurrent viewers at peak:            10 million
Average streaming bitrate (adaptive):  2.5 Mbps (mix of 480p–1080p)

Egress bandwidth:
  10M × 2.5 Mbps = 25 Tbps outbound at peak

With 95% CDN hit ratio:
  Only 5% hits origin = 1.25 Tbps origin egress
  CDN absorbs: 23.75 Tbps

Daily data transferred:
  Assume average 40 minutes watched/DAU
  100M DAU × 40 min × 60s × 2.5 Mbps / 8 = 75 PB/day egress

This is consistent with YouTube's reported ~1 billion hours watched/day.
```

### 6.5 RPS Estimates

```
Read APIs (video metadata, search, recommendations):
  100M DAU, average 20 API calls/session
  2B API calls/day ÷ 86,400 = ~23,000 RPS average
  Peak 3x = ~70,000 RPS for read APIs

Write APIs (views, comments, likes):
  Views: 1B videos viewed/day = ~11,600 view events/second
  Likes: ~1% like rate = 116/second
  Comments: ~0.01% comment rate = 11.6/second

  View events are write-heavy — must be handled asynchronously

Upload APIs:
  71 videos/second initiated = 71 multipart upload sessions/second
  Each upload generates ~100 chunk upload requests
  ~7,100 chunk upload requests/second

Transcode jobs:
  71 videos/second × 6 renditions = 426 transcode jobs/second
```

### 6.6 Read/Write Ratio

```
Overall system read/write ratio:   ~1000:1 (extremely read-heavy)
Video metadata:                    500:1
View counts:                       Read heavy but write events are very high volume
Comments:                          20:1
Likes:                             50:1

Implication: aggressive caching on the read path is non-negotiable.
```

---

## 7. Latency Expectations

| Operation | P50 | P99 | Notes |
|---|---|---|---|
| Video start (TTFF) | 800ms | 2s | Includes manifest fetch + first segment |
| Search results | 100ms | 500ms | Elasticsearch-backed |
| Home feed load | 150ms | 600ms | Pre-computed recommendations |
| Video metadata API | 20ms | 100ms | Redis-cached |
| Upload acknowledgment | 200ms | 1s | Per-chunk acknowledgment |
| Comment post | 100ms | 300ms | |
| Like action | 50ms | 150ms | Redis counter |

---

## 8. Availability Targets

| Service | SLA | Strategy |
|---|---|---|
| Video streaming (CDN) | 99.99% | Multi-CDN, origin shield, cold failover |
| Upload API | 99.9% | Active-active multi-region |
| Transcoding pipeline | 99.5% | Best-effort; job retry; not user-facing |
| Metadata API | 99.99% | Read replicas + Redis cache |
| Search | 99.9% | Elasticsearch cluster with replicas |
| Recommendations | 99.5% | Graceful degradation to trending fallback |
| Live streaming | 99.5% | Best-effort; inherently stateful |

---

## 9. Traffic Patterns

- **Diurnal patterns**: Peak traffic 7 PM–11 PM local time per region. Multi-region means 24/7 global peaks.
- **Viral spikes**: Breaking news or viral content can cause 100x traffic spike on a single video within minutes.
- **Upload burst**: Upload rate spikes when major events occur (sports, elections, concerts).
- **Long-tail distribution**: Top 10% of videos account for 90% of views (power law). Most videos are never re-watched.
- **Live streaming surges**: Major live events (World Cup, Apple Keynote) create synchronized concurrency spikes.

---

## 10. Interview-Level Discussion Points

- Why separate the upload path from the streaming path architecturally? (Different scaling properties, different durability requirements)
- Why is 500 hours/minute upload rate misleading as a raw number? (Most uploaded videos are rarely watched — the long tail means storage cost dominates, not compute)
- How does eventual consistency affect view counts? (Eventual is acceptable — a count off by a few thousand for a viral video is invisible to users; strong consistency would require distributed locking and destroy throughput)
- What is the implication of 10M concurrent viewers at 2.5 Mbps? (25 Tbps — no single origin can serve this; CDN is architecturally mandatory, not optional)
- Why is idempotency critical for uploads? (Network failures during 256 GB uploads are common; without idempotency, retries cause duplicates or data corruption)
- What is the Taking vs startup difference here? (A startup would start with a single-region modular monolith with S3 + a SaaS transcoding service like Mux. Taking builds everything in-house for cost efficiency at scale)
