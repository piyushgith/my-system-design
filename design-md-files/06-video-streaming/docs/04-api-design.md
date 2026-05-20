# 04 — API Design: Video Streaming Platform

---

## Objective

Define the complete API surface for the video streaming platform: upload, streaming, metadata, search, recommendations, engagement, and channel management. Establish versioning strategy, idempotency requirements, error standards, and pagination contracts. This document is the contract between client teams and backend teams.

---

## 1. API Design Principles

| Principle | Application |
|---|---|
| RESTful resource-oriented | URLs represent nouns; HTTP verbs represent actions |
| API Versioning via URL path | `/v1/`, `/v2/` — consumers pin to a version |
| Idempotency on writes | All write operations that can be retried MUST be idempotent |
| Pagination on all list endpoints | Cursor-based pagination for ordered lists; offset for unordered |
| Structured error responses | Consistent error schema across all services |
| Backward compatibility | Additive changes only within a version; no field removal |
| Auth on every endpoint | JWT bearer token required except for public video streams |

---

## 2. Authentication and Authorization

All API requests include:

```
Authorization: Bearer <JWT_ACCESS_TOKEN>
```

Unauthenticated requests to public endpoints (video manifest, search, video page) proceed without auth but may return limited data.

**JWT claims**:
- `sub`: user_id
- `role`: VIEWER / CREATOR / MODERATOR / ADMIN
- `channel_id`: present if role is CREATOR
- `exp`: expiry (15 minutes for access token)
- `jti`: token ID for revocation

**Token refresh**: Refresh token stored in httpOnly cookie (30-day TTL). Access token refreshed via `/v1/auth/refresh`.

---

## 3. Upload APIs

### 3.1 Initiate Upload

```
POST /v1/uploads
```

**Purpose**: Start a new video upload session. Returns chunk URLs for direct-to-S3 multipart upload.

**Request Body**:
```json
{
  "filename": "my_video.mp4",
  "file_size_bytes": 2684354560,
  "content_type": "video/mp4",
  "checksum_sha256": "abc123...",
  "channel_id": "uuid",
  "idempotency_key": "uuid-v4-client-generated"
}
```

**Response 201 Created**:
```json
{
  "upload_id": "upl_abc123",
  "status": "INITIATED",
  "chunks": [
    {
      "chunk_number": 1,
      "byte_range_start": 0,
      "byte_range_end": 10485759,
      "presigned_url": "https://s3.amazonaws.com/...",
      "url_expires_at": "2026-05-17T14:00:00Z"
    }
  ],
  "total_chunks": 256,
  "expires_at": "2026-05-18T12:00:00Z"
}
```

**Idempotency**: The `idempotency_key` field ensures that if the client retries due to a network failure, the same `upload_id` is returned rather than creating a duplicate upload session.

**Taking Detail**: YouTube and Google Drive use a similar resumable upload protocol. The client uploads chunks directly to S3 using presigned URLs — the Upload Service never proxies the bytes, avoiding bandwidth bottleneck on application servers.

---

### 3.2 Complete Upload

```
POST /v1/uploads/{upload_id}/complete
```

**Request Body**:
```json
{
  "parts": [
    { "chunk_number": 1, "etag": "\"abc123\"" },
    { "chunk_number": 2, "etag": "\"def456\"" }
  ]
}
```

**Response 200 OK**:
```json
{
  "upload_id": "upl_abc123",
  "status": "COMPLETE",
  "video_id": "vid_xyz789",
  "processing_status": "QUEUED"
}
```

---

### 3.3 Get Upload Status

```
GET /v1/uploads/{upload_id}
```

**Response**:
```json
{
  "upload_id": "upl_abc123",
  "status": "PROCESSING",
  "video_id": "vid_xyz789",
  "processing": {
    "renditions_complete": ["144p", "360p"],
    "renditions_pending": ["480p", "720p", "1080p"],
    "estimated_completion_seconds": 180
  }
}
```

---

## 4. Video Metadata APIs

### 4.1 Create Video Metadata (during upload)

```
POST /v1/videos
```

**Request**:
```json
{
  "upload_id": "upl_abc123",
  "title": "My Amazing Video",
  "description": "Full description here...",
  "tags": ["travel", "adventure", "4k"],
  "category": "TRAVEL",
  "language": "en",
  "visibility": "PRIVATE",
  "scheduled_publish_at": null
}
```

**Response 201 Created**:
```json
{
  "video_id": "vid_xyz789",
  "status": "DRAFT",
  "created_at": "2026-05-17T12:00:00Z"
}
```

---

### 4.2 Get Video (Public)

```
GET /v1/videos/{video_id}
```

**Response 200 OK**:
```json
{
  "video_id": "vid_xyz789",
  "title": "My Amazing Video",
  "description": "Full description...",
  "channel": {
    "channel_id": "ch_abc",
    "name": "TravelWith Me",
    "handle": "@travelwithme",
    "subscriber_count": 1250000,
    "avatar_url": "https://cdn.example.com/ch_abc/avatar.jpg"
  },
  "duration_seconds": 942,
  "view_count": 4820123,
  "like_count": 95830,
  "comment_count": 2340,
  "published_at": "2026-05-17T10:00:00Z",
  "thumbnail_url": "https://cdn.example.com/vid_xyz789/thumb.jpg",
  "tags": ["travel", "adventure"],
  "category": "TRAVEL",
  "playback": {
    "manifest_url": "https://cdn.example.com/v1/videos/vid_xyz789/manifest.m3u8",
    "drm_required": false
  },
  "viewer_interaction": {
    "liked": false,
    "watch_position_seconds": 0
  }
}
```

---

### 4.3 Update Video Metadata

```
PATCH /v1/videos/{video_id}
```

Only title, description, tags, thumbnail, visibility, category are patchable. Re-uploading the video file requires a new upload session.

**Authorization**: Creator of the video only.

---

### 4.4 List Videos (Channel's Videos)

```
GET /v1/channels/{channel_id}/videos
```

**Query Parameters**:
- `cursor` — base64-encoded pagination cursor
- `limit` — default 20, max 50
- `sort` — `published_at` (desc), `view_count` (desc)
- `visibility` — PUBLIC, PRIVATE (only for channel owner)

**Response**:
```json
{
  "videos": [...],
  "next_cursor": "eyJwdWJsaXNoZWRfYXQiOiIyMDI2LTA1LTE3In0=",
  "has_more": true
}
```

**Pagination**: Cursor-based pagination is mandatory. Offset-based pagination on billions of rows creates full table scans. Cursor encodes the last seen value of the sort field + the last seen id for tie-breaking.

---

## 5. Streaming APIs

### 5.1 Get HLS Master Manifest

```
GET /v1/videos/{video_id}/manifest.m3u8
```

**Purpose**: Returns the HLS master playlist listing all available renditions.

**Response** (Content-Type: application/x-mpegurl):
```
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360,CODECS="avc1.64001e,mp4a.40.2"
/v1/videos/vid_xyz789/360p/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
/v1/videos/vid_xyz789/720p/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
/v1/videos/vid_xyz789/1080p/playlist.m3u8
```

**Caching**: CDN caches manifests with 60-second TTL. Short TTL ensures newly available renditions appear quickly.

**Signed Manifests**: For private or DRM-protected content, manifest URLs are signed with a 4-hour expiry. Unsigned requests return 403.

---

### 5.2 Get Rendition Playlist

```
GET /v1/videos/{video_id}/{rendition}/playlist.m3u8
```

**Response** (HLS Media Playlist):
```
#EXTM3U
#EXT-X-TARGETDURATION:6
#EXT-X-VERSION:3
#EXT-X-PLAYLIST-TYPE:VOD
#EXTINF:6.000,
/segments/vid_xyz789/720p/seg000.ts
#EXTINF:6.000,
/segments/vid_xyz789/720p/seg001.ts
...
#EXT-X-ENDLIST
```

---

### 5.3 Segment Delivery

Segments are served directly by CDN. The CDN fetches from S3 on a cache miss. There is no application-layer segment API — this is a direct CDN-to-S3 path with the CDN Origin Service acting as intermediary for cache misses on manifests.

---

## 6. Search APIs

### 6.1 Video Search

```
GET /v1/search/videos
```

**Query Parameters**:
- `q` — required, search query string, max 500 chars
- `category` — filter by category enum
- `duration` — SHORT (<4min), MEDIUM (4-20min), LONG (>20min)
- `upload_date` — HOUR, TODAY, WEEK, MONTH, YEAR
- `sort` — RELEVANCE (default), VIEW_COUNT, DATE, RATING
- `cursor` — pagination
- `limit` — max 50

**Response**:
```json
{
  "query": "system design interview",
  "total_results": 428000,
  "results": [
    {
      "video_id": "vid_abc",
      "title": "System Design Interview - Top 10 Questions",
      "channel_name": "TechInterviewPro",
      "thumbnail_url": "...",
      "duration_seconds": 3420,
      "view_count": 2840000,
      "published_at": "2026-01-15T00:00:00Z",
      "relevance_score": 0.94
    }
  ],
  "next_cursor": "...",
  "has_more": true
}
```

**Backend**: Elasticsearch query with multi-match on title (boosted), description, tags. Numeric field boosts on view_count and recency.

---

## 7. Recommendation APIs

### 7.1 Get Home Feed

```
GET /v1/recommendations/feed
```

**Query Parameters**:
- `cursor` — pagination cursor
- `limit` — max 30
- `context` — HOME_FEED, SIDEBAR, POST_ROLL

**Response**:
```json
{
  "recommendations": [
    {
      "video_id": "vid_abc",
      "title": "...",
      "thumbnail_url": "...",
      "channel_name": "...",
      "duration_seconds": 542,
      "view_count": 1200000,
      "recommendation_reason": "Based on your watch history"
    }
  ],
  "next_cursor": "...",
  "session_id": "rec_sess_xyz"
}
```

**Fallback**: If recommendation service is unavailable, fall back to trending videos for the user's region.

---

### 7.2 Get Up-Next Videos

```
GET /v1/videos/{video_id}/up-next
```

Returns the list of videos to play after the current video ends (autoplay queue). Context-aware: knows the current video to seed recommendations.

---

## 8. Engagement APIs

### 8.1 Record View Event

```
POST /v1/views
```

**Fire-and-forget**: Client posts this non-blocking; failures are silently retried by client SDKs.

**Request**:
```json
{
  "video_id": "vid_xyz789",
  "session_id": "sess_abc",
  "watch_duration_seconds": 342,
  "watch_percentage": 0.36,
  "client_type": "WEB",
  "idempotency_key": "uuid-v4"
}
```

**Response 202 Accepted** (event queued, not yet processed).

---

### 8.2 Like / Unlike Video

```
POST   /v1/videos/{video_id}/like     → Like
DELETE /v1/videos/{video_id}/like     → Unlike
```

**Idempotency**: POST like is idempotent — liking an already-liked video returns 200 with current state, not an error.

**Response**:
```json
{
  "video_id": "vid_xyz789",
  "liked": true,
  "like_count": 95831
}
```

---

### 8.3 Comment APIs

```
POST   /v1/videos/{video_id}/comments         → Add comment
GET    /v1/videos/{video_id}/comments         → List top-level comments (paginated)
GET    /v1/comments/{comment_id}/replies      → List replies to a comment
PATCH  /v1/comments/{comment_id}              → Edit comment (author only)
DELETE /v1/comments/{comment_id}              → Delete comment (author or moderator)
POST   /v1/comments/{comment_id}/like         → Like a comment
```

**Pagination**: Comments use cursor-based pagination sorted by relevance (like count × recency) or newest first.

---

### 8.4 Subscription APIs

```
POST   /v1/channels/{channel_id}/subscribe   → Subscribe
DELETE /v1/channels/{channel_id}/subscribe   → Unsubscribe
GET    /v1/users/me/subscriptions            → List my subscriptions (paginated)
```

---

## 9. Channel APIs

```
GET    /v1/channels/{channel_id}              → Channel profile
GET    /v1/channels/{channel_id}/about        → Channel about page
PATCH  /v1/channels/{channel_id}              → Update channel (owner only)
GET    /v1/channels/{channel_id}/analytics    → Creator analytics (owner only)
```

---

## 10. Error Response Standards

All errors follow a consistent envelope:

```json
{
  "error": {
    "code": "VIDEO_NOT_FOUND",
    "message": "The requested video does not exist or has been removed.",
    "details": {},
    "request_id": "req_abc123",
    "timestamp": "2026-05-17T12:00:00Z",
    "documentation_url": "https://developers.example.com/errors/VIDEO_NOT_FOUND"
  }
}
```

**HTTP Status Code Mapping**:

| HTTP Code | Usage |
|---|---|
| 200 OK | Successful read |
| 201 Created | Resource created |
| 202 Accepted | Async operation queued |
| 400 Bad Request | Client validation error |
| 401 Unauthorized | Missing or invalid auth token |
| 403 Forbidden | Valid auth but insufficient permission |
| 404 Not Found | Resource does not exist |
| 409 Conflict | State conflict (e.g., video already liked) |
| 410 Gone | Resource permanently removed (DMCA takedown) |
| 422 Unprocessable Entity | Business rule violation |
| 429 Too Many Requests | Rate limit exceeded |
| 503 Service Unavailable | Downstream dependency unavailable |

**410 vs 404**: A DMCA-removed video returns 410 Gone, not 404 — this is legally significant. The resource existed and was deliberately removed.

---

## 11. Versioning Strategy

**URL path versioning**: `/v1/`, `/v2/`

**Rationale**: URL versioning is explicit, easy to route at the gateway level, and clear in logs.

**Deprecation Policy**:
- New major version announced 12 months before old version sunset
- Version sunset communicated via `Sunset` HTTP header on all responses
- Minimum 2 major versions supported concurrently

**Backward Compatibility Rules**:
- New optional fields can be added within a version
- Fields cannot be removed within a version
- Enum values can be added but not removed
- API behavior changes require a new major version

---

## 12. Rate Limiting Strategy

| API Group | Limit | Window |
|---|---|---|
| Video streaming (manifest) | 1,000 req/min | Per client IP |
| Search | 100 req/min | Per user |
| Upload initiation | 10 req/min | Per user |
| Comment posting | 30 req/min | Per user |
| Like/unlike | 60 req/min | Per user |
| View recording | 1 req/min per video | Per session |
| Unauthenticated APIs | 30 req/min | Per IP |

Rate limit headers on every response:
```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 87
X-RateLimit-Reset: 1715956800
```

---

## 13. Idempotency Strategy

| API | Idempotency Mechanism |
|---|---|
| Upload initiation | Client-supplied `idempotency_key` in body; server deduplicates by key with 24h window |
| View recording | Client-supplied `idempotency_key`; server deduplicates in Redis with 24h window |
| Like action | Natural idempotency — second like returns same state |
| Subscribe action | Natural idempotency — second subscribe returns 200 |
| Comment creation | `idempotency_key` in request body; prevents duplicate comment on retry |

---

## 14. Pagination Design

**Cursor-based Pagination** (used for all production list endpoints):

- Cursor encodes: sort field value + entity ID (for tie-breaking)
- Cursor is opaque to clients (base64-encoded JSON)
- Stable under concurrent inserts — no "skipping" items like offset pagination

**Why not offset pagination?**:
- OFFSET N on a billion-row table performs a full scan of N rows just to discard them
- Concurrent inserts shift rows, causing duplicates or gaps

**Keyset Pagination example** (internal SQL pattern):
```
WHERE (published_at, video_id) < (cursor_published_at, cursor_video_id)
ORDER BY published_at DESC, video_id DESC
LIMIT 20
```

---

## 15. OpenAPI / Swagger Planning

- All APIs documented in OpenAPI 3.1 specification
- Spec maintained in a dedicated `api-contracts` repository
- Contract testing enforced via CI: `pact` or `dredd` validates server implementation against spec
- Client SDKs (iOS, Android, Web) auto-generated from spec using OpenAPI Generator
- Spec includes examples for every request/response
- Spec changes require PR review from both backend and client teams

---

## 16. Interview-Level Discussion Points

- Why use cursor-based pagination instead of offset? (Offset pagination is O(N) at the database level — fetching page 1000 of a results set requires scanning 20,000 rows. Cursor pagination is O(log N) using an index seek)
- Why does the view recording API return 202 instead of 200? (View recording is asynchronous — the event is enqueued in Kafka; acknowledgment is deferred. Returning 200 would imply the view was counted, which is false)
- How do you handle upload idempotency across retries where the client sent all chunks but the complete request failed? (The `upload_id` scopes the session; the complete endpoint is idempotent — if called again with the same parts, it checks S3 for the completed multipart upload and returns the already-created video_id)
- What is the risk of client-generated idempotency keys? (Clients may generate duplicate keys accidentally, or maliciously reuse keys to prevent recording. Keys should be UUIDs — server validates format and rejects non-UUIDs)
- How does the 410 Gone status help with DMCA compliance? (DMCA requires that removed content is traceable — 410 communicates "this existed and was intentionally removed", which is important for legal audit trails and for search engine de-indexing)
