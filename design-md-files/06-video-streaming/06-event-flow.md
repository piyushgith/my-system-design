# 06 — Event Flow: Video Streaming Platform

---

## Objective

Define the complete event-driven architecture: all domain events, Kafka topic design, producer/consumer relationships, sequence flows for critical paths, and special-case flows like live streaming and DMCA takedowns. Events are the nervous system of this platform — they decouple services, enable independent scaling, and provide a durable audit trail.

---

## 1. Why Event-Driven Architecture?

At this scale, synchronous request chains would create cascading failures and unacceptable coupling:

- When a video is published, **8+ services** need to react: search indexing, recommendation update, notification fan-out, analytics, CDN warming, moderation review, creator dashboard update
- Making all these synchronous would create a chain where the slowest service (notification fan-out to 100M subscribers) blocks the video publish API response
- Kafka provides durability, replay, and natural backpressure

**Trade-off**: Eventual consistency replaces immediate consistency. The video publish API returns "success" when the event is written to Kafka — not when all 8 consumers have processed it.

---

## 2. Kafka Topic Design

### 2.1 Topic Catalog

| Topic | Partitions | Retention | Producers | Key Consumers |
|---|---|---|---|---|
| `video.upload.initiated` | 16 | 7 days | Upload Service | Analytics |
| `video.upload.completed` | 16 | 7 days | Upload Service | Transcode Orchestrator |
| `video.transcode.requested` | 64 | 3 days | Transcode Orchestrator | Transcode Workers |
| `video.transcode.completed` | 64 | 7 days | Transcode Workers | Orchestrator, Metadata |
| `video.transcode.failed` | 16 | 30 days | Transcode Workers | Alert system, DLQ handler |
| `video.published` | 32 | 30 days | Metadata Service | Search, Recommendations, Notifications, CDN, Analytics |
| `video.updated` | 16 | 7 days | Metadata Service | Search, CDN invalidation |
| `video.deleted` | 16 | 30 days | Metadata Service | CDN, Search, Recommendations |
| `video.removed.moderation` | 16 | 90 days | Moderation Service | CDN, Metadata, Notification, Analytics |
| `view.events` | 128 | 3 days | Engagement Service | Analytics, Recommendation, Counter service |
| `engagement.likes` | 32 | 7 days | Engagement Service | Analytics, Recommendation, Counter |
| `engagement.comments` | 32 | 7 days | Engagement Service | Notification, Moderation, Analytics |
| `engagement.subscriptions` | 32 | 7 days | Engagement Service | Notification, Recommendation, Analytics |
| `user.registered` | 16 | 30 days | User Service | Engagement (create system playlists), Email |
| `user.suspended` | 16 | 90 days | Moderation/User Service | All services |
| `notification.fanout` | 256 | 1 day | Notification Orchestrator | Notification Delivery Workers |
| `dmca.takedown` | 8 | 365 days | Legal/Moderation Service | Metadata, CDN, Search |
| `analytics.aggregated` | 32 | 90 days | Analytics Aggregator | Creator Dashboard, Trending |

### 2.2 Partition Key Strategy

| Topic | Partition Key | Reasoning |
|---|---|---|
| `video.upload.completed` | `video_id` | All transcode events for a video go to same partition — ordering matters |
| `video.transcode.requested` | `video_id` | Keeps rendition jobs for same video near each other |
| `view.events` | `video_id` | Co-locate view events by video for per-video analytics aggregation |
| `engagement.subscriptions` | `channel_id` | Fan-out reads all subscribers for a channel from one partition range |
| `notification.fanout` | `user_id` | Each user's notifications processed in order |

---

## 3. Core Event Schemas

### 3.1 VideoUploaded Event

```json
{
  "event_id": "evt_abc123",
  "event_type": "video.upload.completed",
  "schema_version": "1.0",
  "timestamp": "2026-05-17T12:00:00Z",
  "producer": "upload-service",
  "payload": {
    "video_id": "vid_xyz789",
    "upload_id": "upl_abc123",
    "channel_id": "ch_def456",
    "user_id": "usr_ghi789",
    "raw_s3_bucket": "platform-raw-uploads",
    "raw_s3_key": "uploads/upl_abc123/raw.mp4",
    "file_size_bytes": 2684354560,
    "detected_duration_seconds": 942,
    "detected_framerate": 30.0,
    "detected_resolution": "1920x1080"
  }
}
```

### 3.2 TranscodeCompleted Event

```json
{
  "event_id": "evt_def456",
  "event_type": "video.transcode.completed",
  "schema_version": "1.0",
  "timestamp": "2026-05-17T12:08:30Z",
  "producer": "transcode-worker",
  "payload": {
    "job_id": "job_stu890",
    "video_id": "vid_xyz789",
    "rendition": "1080p",
    "codec": "H.264",
    "segments_count": 157,
    "segment_duration_seconds": 6,
    "output_s3_prefix": "videos/vid_xyz789/1080p/",
    "playlist_s3_key": "videos/vid_xyz789/1080p/playlist.m3u8",
    "duration_seconds": 942,
    "worker_id": "worker-us-east-1-42"
  }
}
```

### 3.3 VideoViewed Event

```json
{
  "event_id": "evt_ghi012",
  "event_type": "view.recorded",
  "schema_version": "1.1",
  "timestamp": "2026-05-17T14:22:10Z",
  "producer": "engagement-service",
  "payload": {
    "view_id": "vw_jkl345",
    "video_id": "vid_xyz789",
    "viewer_user_id": "usr_mno678",
    "session_id": "sess_pqr901",
    "watch_duration_seconds": 342,
    "watch_percentage": 0.363,
    "client_type": "WEB",
    "country_code": "US",
    "referrer_type": "RECOMMENDATION",
    "ip_address_hash": "sha256_hashed_ip",
    "idempotency_key": "ide_stu234"
  }
}
```

---

## 4. Upload-to-Publish Flow (Full Sequence)

```mermaid
sequenceDiagram
    participant C as Creator
    participant US as Upload Service
    participant K as Kafka
    participant TO as Transcode Orchestrator
    participant TW as Transcode Workers (×6)
    participant MS as Metadata Service
    participant SS as Search Service
    participant NS as Notification Service
    participant CDN as CDN Origin

    C->>US: Initiate upload (POST /v1/uploads)
    US-->>C: upload_id + presigned chunk URLs

    loop Chunk uploads (directly to S3)
        C->>S3: PUT chunk via presigned URL
    end

    C->>US: Complete upload (POST /v1/uploads/{id}/complete)
    US->>S3: Complete S3 multipart upload
    US->>K: PUBLISH video.upload.completed
    US-->>C: 202 Accepted — processing started

    K->>TO: CONSUME video.upload.completed
    TO->>K: PUBLISH × 6 video.transcode.requested (one per rendition)

    par Parallel transcoding (6 workers simultaneously)
        K->>TW: CONSUME video.transcode.requested (144p)
        TW->>S3: READ raw video
        TW->>S3: WRITE 144p segments + playlist
        TW->>K: PUBLISH video.transcode.completed (144p)

        K->>TW: CONSUME video.transcode.requested (360p)
        TW->>S3: READ raw video
        TW->>S3: WRITE 360p segments + playlist
        TW->>K: PUBLISH video.transcode.completed (360p)

        Note over TW: ... 480p, 720p, 1080p, 4K similarly
    end

    K->>TO: CONSUME video.transcode.completed (144p) — first rendition
    TO->>MS: Update: 144p ready
    MS->>K: PUBLISH video.processing.first_rendition_ready
    Note over MS: Video can be viewed at 144p now

    loop As each rendition completes
        K->>TO: CONSUME video.transcode.completed
        TO->>MS: Update rendition status
    end

    Note over TO: All 6 renditions complete

    TO->>K: PUBLISH video.transcode.all_complete
    K->>MS: CONSUME — set video status to READY

    C->>MS: PATCH /v1/videos/{id} (set visibility to PUBLIC)
    MS->>K: PUBLISH video.published
    MS-->>C: 200 OK

    par Fan-out on VideoPublished
        K->>SS: CONSUME video.published — index in Elasticsearch
        K->>NS: CONSUME video.published — fan-out to subscribers
        K->>CDN: CONSUME video.published — warm manifest cache
    end
```

---

## 5. View Event Flow

```mermaid
sequenceDiagram
    participant V as Viewer Client
    participant ES as Engagement Service
    participant K as Kafka
    participant AC as Analytics Consumer
    participant RC as Recommendation Consumer
    participant CC as Counter Consumer
    participant CASS as Cassandra
    participant REDIS as Redis

    V->>ES: POST /v1/views (async, fire-and-forget)
    ES->>ES: Validate: rate limit, dedup check (idempotency_key)
    ES->>K: PUBLISH view.events (partition by video_id)
    ES-->>V: 202 Accepted

    par Parallel consumption
        K->>CC: CONSUME view.events
        CC->>REDIS: INCR video:views:{video_id}
        CC->>REDIS: PFADD video:unique_viewers:{video_id} {user_id}
        CC->>REDIS: ZINCRBY trending:{region}:{hour} {score} {video_id}

        K->>AC: CONSUME view.events
        AC->>CASS: INSERT into view_events partition
        AC->>CASS: UPDATE video_daily_stats COUNTER

        K->>RC: CONSUME view.events
        RC->>RC: Update user feature vector
        RC->>REDIS: Invalidate rec:feed:{user_id} cache
    end

    Note over CC,REDIS: Every 60 seconds:
    CC->>REDIS: GET video:views:{video_id}
    CC->>PG: UPDATE videos SET view_count = {redis_value}
```

---

## 6. Notification Fan-Out Flow

This is the hardest scaling problem in the engagement domain. A creator with 100M subscribers publishes a video — how do you notify 100M people within minutes?

```mermaid
sequenceDiagram
    participant K as Kafka
    participant NO as Notification Orchestrator
    participant SR as Subscription Reader (Cassandra)
    participant NK as notification.fanout topic
    participant NW as Notification Workers (×1000)
    participant PN as Push Notification Gateway (FCM/APNs)
    participant WS as WebSocket Server
    participant EM as Email Service

    K->>NO: CONSUME video.published (channel_id: ch_abc)
    NO->>SR: Fetch subscriber_ids for channel ch_abc (paginated, 1000/page)
    
    loop For each batch of 1000 subscribers
        NO->>NK: PUBLISH notification.fanout ×1000 messages
        Note over NK: 100M subscribers = 100K batches = 100K messages
    end

    par 1000 parallel workers
        NK->>NW: CONSUME notification.fanout
        NW->>NW: Check user notification preference
        NW->>NW: Check user active status
        alt Preference = ALL
            NW->>PN: Send push notification
            NW->>EM: Queue email (batched)
        else Preference = PERSONALIZED
            NW->>NW: Check if video matches user interest
            NW->>PN: Send push if relevant
        else Preference = NONE
            NW->>NW: Skip
        end
    end
```

**Key Design Point**: The fan-out is accomplished via Kafka itself. The Notification Orchestrator produces 100M messages (in batches) to the `notification.fanout` topic. 1000 parallel consumer workers each process ~100K messages. At 1000 workers processing 1000 notifications/second each = 1M notifications/second. 100M notifications delivered in ~100 seconds.

**FAANG Reality**: YouTube has a "lazy fan-out" model — notifications are not pushed to all subscribers immediately. High-subscriber creators use a "pull on demand" approach where the notification is written to a shared store and pulled when the subscriber opens the app.

---

## 7. DMCA Takedown Flow

DMCA requires takedowns to happen "expeditiously" (typically hours, but platforms often process within minutes for critical violations).

```mermaid
sequenceDiagram
    participant LGL as Legal/DMCA Tool
    participant MOD as Moderation Service
    participant K as Kafka
    participant META as Metadata Service
    participant CDN as CDN Origin Service
    participant SEARCH as Search Service
    participant NOTIFY as Notification Service

    LGL->>MOD: POST /v1/dmca/claims (video_id, claimant, claim_reason)
    MOD->>MOD: Validate claim authenticity
    MOD->>META: Immediately set video visibility = DMCA_HOLD
    META-->>MOD: Updated
    MOD->>K: PUBLISH dmca.takedown event
    MOD-->>LGL: 202 Accepted — takedown initiated

    par Immediate parallel actions
        K->>CDN: CONSUME dmca.takedown
        CDN->>CDN: Purge all CDN edge caches for video_id
        CDN->>CDN: Return 410 Gone for any cached segments

        K->>SEARCH: CONSUME dmca.takedown
        SEARCH->>ES: Delete video from index immediately

        K->>META: CONSUME dmca.takedown
        META->>PG: SET visibility = 'DMCA_REMOVED', status = 'REMOVED'
    end

    K->>NOTIFY: CONSUME dmca.takedown
    NOTIFY->>NOTIFY: Notify creator of DMCA claim
    NOTIFY->>NOTIFY: Provide counter-notice instructions
```

**Critical**: CDN cache purge is synchronous (not via Kafka) for DMCA — the CDN API is called immediately before the Kafka event is published. This ensures the video is unreachable from CDN before any async consumers process the event.

---

## 8. Live Streaming Event Flow

Live streaming has different characteristics: ultra-low latency, no transcoding delay, real-time segment generation.

```mermaid
sequenceDiagram
    participant CR as Creator (OBS / RTMP)
    participant RTMP as RTMP Ingest Server
    participant TS as Transcoding Service (Live)
    participant S3 as S3 Live Bucket
    participant CDN as CDN Live Origin
    participant V as Viewer
    participant K as Kafka
    participant AC as Analytics

    CR->>RTMP: Push RTMP stream (stream_key)
    RTMP->>RTMP: Validate stream key
    RTMP->>K: PUBLISH live.stream.started
    RTMP->>TS: Forward raw MPEG-TS stream

    loop Every 2 seconds (HLS segment)
        TS->>S3: PUT live segment (2s .ts chunk)
        TS->>S3: UPDATE live playlist (rolling window, last 5 segments)
        S3->>CDN: Push segment to CDN edge (or CDN pulls)
    end

    V->>CDN: GET /live/{stream_id}/playlist.m3u8
    CDN-->>V: Live playlist (last 5 segments = 10s buffer)
    
    loop Every 2 seconds
        V->>CDN: GET next segment
        CDN-->>V: 2s of live video
        Note over V,CDN: ~10s latency (5 segments × 2s)
    end

    V->>K: PUBLISH live.view.event (every 30s)
    K->>AC: CONSUME live.view.event
    AC->>REDIS: Increment live concurrent viewers counter

    CR->>RTMP: End stream
    RTMP->>TS: Signal stream end
    TS->>S3: Write final segment + #EXT-X-ENDLIST to playlist
    RTMP->>K: PUBLISH live.stream.ended
    K->>META: CONSUME — convert live recording to VOD (optional)
```

---

## 9. Exactly-Once Semantics for View Counting

View counts are the most visible metric on the platform. Duplicate counts (producer retries) or lost counts (consumer crashes) must be handled:

**Problem**: Kafka at-least-once delivery means view events can be delivered multiple times if the consumer crashes after processing but before committing the offset.

**Solution**: Idempotent consumer design:

```
Step 1: Consumer reads view event with idempotency_key
Step 2: Check Redis SET nx:view:{idempotency_key} = "1" EX 86400
        - If SET returns 1 → first time processing → proceed
        - If SET returns 0 → already processed → skip (deduplicate)
Step 3: INCR Redis counter + write to Cassandra
Step 4: Commit Kafka offset
```

This achieves effectively-exactly-once semantics at the application level (not Kafka's transactional exactly-once which has throughput overhead).

---

## 10. Consumer Group Strategy

| Consumer Group | Topic(s) | Consumer Count | Purpose |
|---|---|---|---|
| `transcode-orchestrator` | `video.upload.completed` | 8 | Single orchestrator group |
| `transcode-workers-144p` | `video.transcode.requested` | 32 | Filter for rendition=144p |
| `transcode-workers-1080p` | `video.transcode.requested` | 32 | Filter for rendition=1080p |
| `analytics-view-counter` | `view.events` | 64 | Redis counter updates |
| `analytics-cassandra-writer` | `view.events` | 64 | Cassandra persistence |
| `recommendation-updater` | `view.events`, `engagement.likes` | 32 | Feature vector updates |
| `search-indexer` | `video.published`, `video.updated`, `video.deleted` | 16 | Elasticsearch sync |
| `notification-orchestrator` | `video.published`, `engagement.subscriptions` | 16 | Fan-out initiation |
| `notification-delivery` | `notification.fanout` | 1024 | Push/email delivery |
| `moderation-auto-review` | `video.published`, `engagement.comments` | 32 | ML content review |
| `cdn-cache-manager` | `video.published`, `dmca.takedown` | 8 | Cache warm/invalidate |
| `dead-letter-handler` | `*.dlq` | 4 | Retry or alert |

---

## 11. Dead Letter Queue Strategy

| DLQ Topic | Trigger | Handler Action |
|---|---|---|
| `video.transcode.requested.dlq` | 3 consecutive failures | Alert on-call + create support ticket for creator |
| `view.events.dlq` | Consumer crash after 3 retries | Log to analytics audit; view count may be off by this amount |
| `notification.fanout.dlq` | Push gateway failure | Log; acceptable — notifications are best-effort |
| `search-index.dlq` | Elasticsearch unavailable | Replay when ES recovers |
| `dmca.takedown.dlq` | CDN purge failure | IMMEDIATE alert; manual intervention required (legal risk) |

---

## 12. Schema Registry and Compatibility

All Kafka events are registered in Confluent Schema Registry:

- **Backward compatibility**: New optional fields can be added; existing fields cannot be renamed/removed within a schema version
- **Consumer contract testing**: Each consumer has a contract test that validates it can deserialize all historical schema versions
- **Event versioning**: `schema_version` field in every event envelope enables gradual consumer migration

---

## 13. Interview-Level Discussion Points

- How do you prevent the notification fan-out from overwhelming FCM/APNs? (Rate limiting at the Notification Worker level; batch notifications to same user; exponential backoff on gateway errors; circuit breaker per delivery channel)
- What happens if the Kafka topic for view events falls behind (consumer lag grows)? (Scale up consumer instances — Kafka consumers scale horizontally up to the number of partitions; if 128 partitions, can have 128 consumers in the group. Beyond that, add partitions — but this is disruptive on live topics)
- Why not use a separate message queue (SQS/RabbitMQ) for transcode jobs instead of Kafka? (Kafka provides log compaction and replay — if the Metadata service was down when TranscodeCompleted events fired, it can replay from its last offset. SQS messages are deleted after consumption — no replay possible)
- How do you handle the case where a producer publishes an event but the downstream database write fails? (Outbox Pattern: the producer writes the event to a local `outbox` table in the same database transaction as the business operation. A separate Outbox Publisher reads new outbox rows and publishes to Kafka. This ensures the event is published if and only if the DB write succeeds)
- How long should view events be retained in Kafka? (3 days minimum for operational replay; analytics pipeline should process events within hours, not days; longer retention increases storage cost. Keep 3 days for safety margin against consumer outages)
