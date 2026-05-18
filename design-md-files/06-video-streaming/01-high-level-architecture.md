# 01 — High-Level Architecture: Video Streaming Platform

---

## Objective

Define the overarching system architecture, justify the choice of microservices over alternatives, decompose the system into its major components, and describe the end-to-end flow from video upload through transcoding to global delivery. Establish the migration path so this design can be understood as an evolution, not a greenfield fantasy.

---

## 1. Architecture Decision: Why Microservices?

At the scale of YouTube/Netflix, microservices are not a preference — they are a structural necessity. The justification is rooted in the **independent scaling dimensions** of each subsystem.

| Subsystem | Primary Constraint | Scaling Axis |
|---|---|---|
| Upload service | Network I/O, storage write throughput | Horizontal (stateless per chunk) |
| Transcoding workers | CPU, GPU (H.264/H.265 encoding) | Burst-heavy horizontal (HPA on queue depth) |
| CDN / delivery | Bandwidth, geographic proximity | Edge node count + CDN provider capacity |
| Metadata service | Read-heavy, low latency | Read replicas + cache |
| Search | Write throughput of index, query latency | Elasticsearch horizontal shards |
| Recommendations | ML inference latency, batch precompute | GPU-backed inference cluster |
| View/Analytics | Write-heavy event stream | Kafka + stream processing |
| User/Auth | Read-heavy, consistency-critical | Read replicas, stateless JWT |

If these were colocated in a monolith, you cannot independently scale transcoding (CPU-heavy) from metadata reads (I/O-light). Deploying a change to the recommendation algorithm would require redeploying the entire upload service. Fault isolation is impossible — a memory leak in transcoding workers crashes the API.

### When NOT to use Microservices

- Teams of fewer than 20 engineers: network overhead, service mesh complexity, and distributed debugging are productivity killers.
- Early product phase where domain boundaries are unknown: prematurely decomposing leads to chatty inter-service calls and distributed monoliths.
- When consistency requirements are very high: cross-service transactions require Saga patterns which add complexity.

**FAANG vs Startup**: Netflix and YouTube run hundreds of microservices with dedicated teams per service. A startup building a video platform should start with a **modular monolith with clear package boundaries** (see Section 4 below — Migration Path).

---

## 2. System Decomposition

### Core Services

| Service | Responsibility |
|---|---|
| **API Gateway** | TLS termination, auth token validation, rate limiting, request routing |
| **Upload Service** | Accepts chunked uploads, validates, stores to object storage, emits events |
| **Transcoding Orchestrator** | Picks up upload events, partitions transcode jobs, tracks progress |
| **Transcoding Workers** | Stateless CPU-bound workers that encode a single rendition |
| **CDN Origin Service** | Serves video segments and manifests; acts as CDN origin |
| **Metadata Service** | CRUD for videos, channels, playlists; serves video pages |
| **User Service** | Registration, profiles, authentication, JWT issuance |
| **Search Service** | Indexes video metadata; serves full-text and faceted search |
| **Recommendation Service** | Serves pre-computed home feed, up-next videos |
| **Engagement Service** | Handles likes, comments, subscriptions, watch history |
| **Analytics Service** | Ingests view events; computes creator analytics |
| **Notification Service** | Fan-out notifications to subscribers |
| **Moderation Service** | Automated + manual content review pipeline |

### Supporting Infrastructure

| Component | Role |
|---|---|
| Object Storage (S3) | Durable storage for raw uploads, encoded segments, thumbnails |
| PostgreSQL | Metadata persistence (videos, users, channels, comments) |
| Redis Cluster | Caching, view counters, session state, pub/sub |
| Kafka | Event backbone: upload events, view events, transcode events |
| Elasticsearch | Full-text search index, video discovery |
| CDN (Cloudfront/Akamai) | Edge delivery of video segments and thumbnails |
| Kubernetes | Container orchestration, autoscaling of workers |

---

## 3. Full Architecture Diagram

```mermaid
graph TB
    subgraph Clients
        WEB[Web Browser]
        MOB[Mobile App]
        TV[Smart TV App]
    end

    subgraph Edge Layer
        CDN[CDN Edge PoPs\nAkamai / CloudFront\n200+ Locations]
    end

    subgraph API Layer
        GW[API Gateway\nTLS / Auth / Rate Limit]
    end

    subgraph Upload Pipeline
        US[Upload Service\nChunked / Resumable]
        OS_RAW[Object Storage\nRaw Uploads Bucket]
        TO[Transcoding Orchestrator]
        TW1[Transcode Worker\n144p / 360p]
        TW2[Transcode Worker\n480p / 720p]
        TW3[Transcode Worker\n1080p / 4K]
        OS_ENC[Object Storage\nEncoded Segments Bucket]
        THUMB[Thumbnail Generator]
    end

    subgraph Streaming Path
        OS_ENC
        CDN_ORIG[CDN Origin Service\nManifest Builder]
    end

    subgraph Platform Services
        META[Metadata Service\nPostgreSQL + Redis]
        USR[User Service\nPostgreSQL + JWT]
        ENG[Engagement Service\nLikes / Comments / Subs]
        SRCH[Search Service\nElasticsearch]
        REC[Recommendation Service\nPrecomputed Scores]
        ANA[Analytics Service\nKafka Streams]
        NOTIF[Notification Service\nFan-out]
        MOD[Moderation Service\nAI + Human Queue]
    end

    subgraph Event Bus
        KAFKA[Apache Kafka\nEvent Backbone]
    end

    subgraph Data Stores
        PG[(PostgreSQL\nMetadata DB)]
        REDIS[(Redis Cluster\nCache + Counters)]
        ES[(Elasticsearch\nSearch Index)]
        CASS[(Cassandra / Scylla\nView Events)]
    end

    WEB & MOB & TV -->|HTTPS| CDN
    CDN -->|Cache Miss| CDN_ORIG
    CDN_ORIG --> OS_ENC

    WEB & MOB & TV -->|API Calls| GW
    GW --> US
    GW --> META
    GW --> USR
    GW --> ENG
    GW --> SRCH
    GW --> REC

    US --> OS_RAW
    US -->|VideoUploaded event| KAFKA
    KAFKA --> TO
    TO --> TW1 & TW2 & TW3
    TW1 & TW2 & TW3 --> OS_ENC
    TW1 & TW2 & TW3 --> THUMB
    TW1 & TW2 & TW3 -->|TranscodeCompleted| KAFKA
    KAFKA --> META
    META --> PG
    META --> REDIS

    ENG -->|ViewEvent| KAFKA
    KAFKA --> ANA
    ANA --> CASS

    KAFKA --> NOTIF
    KAFKA --> MOD

    SRCH --> ES
    KAFKA -->|VideoPublished| ES
```

---

## 4. Upload-to-Publish Flow (Sequence)

```mermaid
sequenceDiagram
    participant C as Creator Client
    participant GW as API Gateway
    participant US as Upload Service
    participant S3R as S3 Raw Bucket
    participant K as Kafka
    participant TO as Transcode Orchestrator
    participant TW as Transcode Workers
    participant S3E as S3 Encoded Bucket
    participant MS as Metadata Service
    participant CDN as CDN Origin

    C->>GW: POST /uploads (initiate, filename, size)
    GW->>US: Route upload initiation
    US-->>C: Return upload_id + presigned chunk URLs

    loop Per chunk (5–10 MB each)
        C->>S3R: PUT chunk (via presigned URL, direct to S3)
        S3R-->>C: 200 OK (ETag per chunk)
    end

    C->>US: POST /uploads/{upload_id}/complete (part ETags)
    US->>S3R: Complete multipart upload
    S3R-->>US: Final object URL
    US->>K: Publish VideoUploaded event
    US-->>C: Upload complete, video processing

    K->>TO: Consume VideoUploaded event
    TO->>K: Publish 6× TranscodeRequested events (one per rendition)

    par Parallel Transcoding
        K->>TW: Consume TranscodeRequested (144p)
        TW->>S3R: Read raw video
        TW->>S3E: Write HLS segments (144p)
        TW->>K: TranscodeCompleted (144p)

        K->>TW: Consume TranscodeRequested (1080p)
        TW->>S3R: Read raw video
        TW->>S3E: Write HLS segments (1080p)
        TW->>K: TranscodeCompleted (1080p)
    end

    K->>MS: Consume TranscodeCompleted events
    MS->>MS: Update video status per rendition
    MS->>K: Publish VideoPublished (when minimum renditions ready)

    K->>CDN: Invalidate/warm manifest cache
    MS-->>C: Notify: Video is now live
```

---

## 5. Video Playback Flow

```mermaid
sequenceDiagram
    participant V as Viewer Client
    participant CDN as CDN Edge
    participant ORIG as CDN Origin Service
    participant REDIS as Redis
    participant PG as PostgreSQL

    V->>CDN: GET /videos/{video_id}/manifest.m3u8
    alt Cache Hit
        CDN-->>V: Return cached manifest (HLS playlist)
    else Cache Miss
        CDN->>ORIG: Fetch manifest
        ORIG->>REDIS: Get video metadata + segment list
        alt Redis hit
            REDIS-->>ORIG: Return metadata
        else Redis miss
            ORIG->>PG: Query video record
            PG-->>ORIG: Video metadata + segment paths
            ORIG->>REDIS: Cache metadata (TTL 5 min)
        end
        ORIG-->>CDN: Build and return master manifest
        CDN->>CDN: Cache manifest (TTL 60s)
        CDN-->>V: Return manifest
    end

    V->>CDN: GET /segments/{video_id}/720p/seg001.ts
    CDN-->>V: Video segment (cached at edge)

    V->>V: Adaptive bitrate logic: monitor bandwidth
    V->>CDN: GET /segments/{video_id}/480p/seg002.ts (downgrade)
    CDN-->>V: Lower quality segment

    V->>GW: POST /views (view event, async)
    Note over GW: Fire-and-forget, non-blocking
```

---

## 6. Service Communication Patterns

| From → To | Protocol | Pattern | Why |
|---|---|---|---|
| Client → API Gateway | HTTPS/HTTP2 | Request/Response | Standard |
| Gateway → Upload Service | HTTP | Sync REST | Needs immediate upload_id back |
| Upload Service → Kafka | Kafka Producer | Async event | Decouple upload from transcode |
| Transcode Orchestrator → Workers | Kafka | Async job dispatch | Backpressure-safe, durable queue |
| Services → PostgreSQL | JDBC | Sync query | ACID requirements |
| Services → Redis | TCP | Sync (microseconds) | Cache reads must be synchronous |
| Metadata → Kafka | Kafka Consumer | Event-driven update | React to transcode completion |
| Services → Elasticsearch | REST | Async indexing | Write-behind; eventual consistency OK |
| Gateway → Other Services | HTTP/gRPC | Sync REST / RPC | Real-time user response needed |
| Analytics → Kafka | Kafka Consumer | Stream processing | High-throughput event processing |

---

## 7. Why NOT a Modular Monolith at This Scale?

| Concern | Monolith Problem | Microservice Solution |
|---|---|---|
| Transcoding CPU | Transcoding workers would compete with API handlers for CPU | Isolated containers with dedicated vCPU limits |
| Deployment velocity | 50+ teams cannot deploy to a single codebase | Independent deployment per service |
| Fault isolation | Transcode crash kills the API | Separate process boundaries |
| Language/runtime choice | Monolith forces one language | Python workers for ML, Java for API, Go for gateway |
| Data ownership | All services share one DB schema — migrations become a bottleneck | Each service owns its schema |

---

## 8. Migration Path: Monolith → Microservices

This is a critical interview point. Nobody starts with 15 microservices.

```
Phase 1 (MVP): Modular Monolith
  - Single Spring Boot app with clean package boundaries
  - SaaS transcoding (Mux, AWS MediaConvert)
  - Single PostgreSQL
  - S3 for video storage
  - Basic CDN

Phase 2 (V1 — Traffic grows): Strangler Fig Pattern
  - Extract Upload Service first (easiest boundary, I/O-bound, isolated)
  - Extract User/Auth Service (security isolation, can become OAuth2 server)
  - Add Kafka for internal events (prepare for async)

Phase 3 (V2 — Scale pressure): Extract Hot Services
  - Extract Analytics (view events overwhelm main DB)
  - Extract Search (Elasticsearch cluster independent of app)
  - Extract Transcoding Orchestrator + Workers

Phase 4 (V3 — Full Microservices): Complete Decomposition
  - Recommendation, Notification, Moderation extracted
  - Service mesh (Istio/Linkerd) for observability + mTLS
  - gRPC for internal service-to-service calls
```

---

## 9. Key Architecture Principles

- **Upload path and read path are physically separated** — different services, different infrastructure. An upload spike does not impact viewer experience.
- **CDN is not optional** — at 25 Tbps peak egress, CDN edge servers absorb traffic that no origin cluster could serve.
- **Kafka is the nervous system** — every state change in the system produces an event; consumers are decoupled and independently scalable.
- **Object storage is immutable after encoding** — segments are written once, read millions of times. This maps perfectly to S3-class storage semantics.
- **Graceful degradation** — if recommendations are down, serve trending videos. If search is slow, return cached results. The video playback path must never be affected by peripheral service failures.

---

## 10. Interview-Level Discussion Points

- Why does the upload go directly to S3 via presigned URLs instead of through the Upload Service? (Bypass application server bandwidth limitations; S3 multipart upload is designed for this)
- How do you handle a partial upload where only 60% of chunks arrived before the client disconnected? (Upload Service maintains upload session state; client can resume using upload_id and re-upload only missing chunks using byte ranges)
- Why is the CDN Origin Service a separate service and not just S3 directly? (Manifest generation is dynamic — it needs to check which renditions are available, sign URLs, inject DRM tokens; pure S3 cannot do this)
- What is the cost of the API Gateway being a single component? (It is a potential bottleneck and single point of failure — deploy as a cluster; use L7 load balancer in front of it; consider per-region gateway instances)
- How does the architecture handle the "thundering herd" on a viral video? (CDN absorbs 95%+ traffic; origin is shielded; manifest cached; Redis counters absorb like/view writes — see caching strategy)
