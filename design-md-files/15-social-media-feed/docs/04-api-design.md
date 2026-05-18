# 04 — API Design: Social Media Feed System

## Objective

Define the REST API contracts for all public-facing and internal service APIs, including versioning strategy, pagination design, idempotency, error handling, and backward compatibility. The API design must support mobile clients, web clients, and third-party developers.

---

## API Design Principles

1. **REST with resource-oriented URLs**: Nouns, not verbs. `/tweets` not `/createTweet`
2. **Cursor-based pagination everywhere**: Offset pagination breaks at scale and under concurrent writes
3. **Idempotency keys on mutations**: Tweet creation and likes must be idempotent to handle mobile retries
4. **Versioning via URL path**: `/api/v1/` — simple, explicit, visible in logs and proxies
5. **Consistent error envelope**: All errors return the same JSON structure
6. **Hypermedia hints**: Include `next_cursor` in paginated responses — clients don't construct URLs

---

## Versioning Strategy

| Strategy | Decision |
|---|---|
| URL path versioning (`/v1/`) | Selected — explicit, cache-friendly, easy to route at gateway |
| Header versioning (`Accept: application/vnd.api+json;version=1`) | Rejected — harder to debug, not cache-friendly |
| Query param versioning (`?version=1`) | Rejected — pollutes query string, accidental omission causes bugs |

**Deprecation Policy**: A version is supported for minimum 18 months after the next version is released. Sunset headers (`Sunset: Sat, 01 Jan 2028 00:00:00 GMT`) are added to deprecated endpoints.

---

## Authentication

All APIs require a Bearer token (JWT) in the `Authorization` header. The API Gateway validates the JWT before forwarding to downstream services. Services receive `X-User-ID` and `X-User-Roles` as trusted headers injected by the gateway.

---

## Core API Endpoints

### Tweet APIs

```
POST   /api/v1/tweets                  Create a new tweet
GET    /api/v1/tweets/{tweet_id}       Get a single tweet
DELETE /api/v1/tweets/{tweet_id}       Soft-delete a tweet
PATCH  /api/v1/tweets/{tweet_id}       Edit tweet (within edit window)
GET    /api/v1/users/{user_id}/tweets  Get user's timeline (their posts)
POST   /api/v1/tweets/{tweet_id}/retweets    Retweet
DELETE /api/v1/tweets/{tweet_id}/retweets    Undo retweet
```

#### POST /api/v1/tweets — Create Tweet

**Request**:
```
Headers:
  Authorization: Bearer <jwt>
  Idempotency-Key: <uuid>   ← client-generated, prevents double-submit

Body:
{
  "content": "Hello world #test @user",
  "media_ids": ["media-uuid-1"],
  "reply_to_tweet_id": null,
  "quoted_tweet_id": null,
  "visibility": "PUBLIC"
}
```

**Response (201 Created)**:
```
{
  "tweet_id": "1234567890123456789",
  "author": { "user_id": "...", "username": "piyush", "display_name": "Piyush" },
  "content": "Hello world #test @user",
  "created_at": "2026-05-18T10:30:00Z",
  "like_count": 0,
  "retweet_count": 0,
  "reply_count": 0,
  "media": [],
  "tweet_type": "ORIGINAL",
  "visibility": "PUBLIC"
}
```

**Idempotency**: The `Idempotency-Key` header is stored in Redis with a 24-hour TTL. If the same key is submitted twice within 24 hours, the second request returns the original response without creating a new tweet. This handles mobile clients retrying after a network timeout.

---

### Feed APIs

```
GET /api/v1/feed/home          Get authenticated user's home timeline
GET /api/v1/feed/mentions      Get mentions feed
```

#### GET /api/v1/feed/home — Home Timeline (Core Endpoint)

**Request**:
```
Headers:
  Authorization: Bearer <jwt>

Query Parameters:
  cursor     (string, optional)   Opaque pagination cursor. Absent = first page.
  limit      (integer, 1-100)     Default 20, max 100
  include_retweets  (boolean)     Default true
```

**Response (200 OK)**:
```
{
  "tweets": [
    {
      "tweet_id": "1234567890123456789",
      "author": { ... },
      "content": "...",
      "created_at": "2026-05-18T10:30:00Z",
      "like_count": 42,
      "retweet_count": 7,
      "reply_count": 3,
      "viewer_has_liked": true,
      "viewer_has_retweeted": false,
      "media": [ { "url": "https://cdn.example.com/...", "type": "image/jpeg" } ],
      "quoted_tweet": { ... }
    },
    ...
  ],
  "next_cursor": "eyJ0d2VldF9pZCI6IjEyMzQ1NiIsInRzIjoiMjAyNi0wNS0xOCJ9",
  "has_more": true,
  "count": 20
}
```

**Cursor Design**: The cursor encodes `{tweet_id, timestamp}` as a base64 JSON blob. On the next request, the Feed Service uses these to fetch entries with `score < cursor.timestamp` (for chronological) or `score < cursor.score` (for ranked feeds). Cursors are opaque to clients — they must not be constructed or parsed by clients.

---

### Follow Graph APIs

```
POST   /api/v1/users/{user_id}/follows     Follow a user
DELETE /api/v1/users/{user_id}/follows     Unfollow a user
GET    /api/v1/users/{user_id}/followers   List followers (paginated)
GET    /api/v1/users/{user_id}/following   List following (paginated)
POST   /api/v1/users/{user_id}/blocks      Block a user
DELETE /api/v1/users/{user_id}/blocks      Unblock a user
POST   /api/v1/users/{user_id}/mutes       Mute a user
```

#### POST /api/v1/users/{user_id}/follows — Follow

**Idempotency**: Following someone you already follow returns 200 (not 201) and no error — idempotent by design.

**Response (201 Created)**:
```
{
  "follower_id": "your-user-id",
  "followee_id": "{user_id}",
  "created_at": "2026-05-18T10:30:00Z",
  "following": true
}
```

**Side Effects (async)**:
- Publishes `user.followed` event to Kafka
- Fanout Service subscribes and retroactively fills the follower's timeline with the last N tweets from the new followee
- Notification Service creates a follow notification for the followee

---

### Engagement APIs

```
POST   /api/v1/tweets/{tweet_id}/likes    Like a tweet
DELETE /api/v1/tweets/{tweet_id}/likes    Unlike a tweet
GET    /api/v1/tweets/{tweet_id}/likes    List likers (paginated)
```

---

### Search & Trending APIs

```
GET /api/v1/search/tweets?q=...&cursor=...   Full-text tweet search
GET /api/v1/search/users?q=...               User search
GET /api/v1/trending                          Get trending topics
GET /api/v1/trending?location=US             Location-filtered trending
```

---

### User APIs

```
GET    /api/v1/users/{user_id}           Get user profile
PATCH  /api/v1/users/me                  Update own profile
GET    /api/v1/users/me                  Get own profile
POST   /api/v1/users/me/avatar           Upload profile image
```

---

## Pagination Design

### Why Cursor-Based, Not Offset-Based

| Dimension | Offset (`?page=3&size=20`) | Cursor (`?cursor=eyJ...`) |
|---|---|---|
| Consistency | Breaks when new tweets are inserted — same tweet can appear twice | Stable — cursor anchors to a specific position |
| Database performance | `OFFSET 1000` reads and discards 1000 rows | Starts exactly where it left off |
| Infinite scroll | Breaks badly | Designed for this pattern |
| Implementation | Simpler | Slightly more complex but worth it |
| Page jumping | Possible | Not supported (by design) |

### Cursor Implementation Detail

A cursor encodes the last seen tweet's (tweet_id, score). On the next page request:
- For chronological feeds: `WHERE score < cursor.score ORDER BY score DESC LIMIT 20`
- For Redis: `ZREVRANGEBYSCORE timeline:{user_id} cursor.score -inf LIMIT 20`
- For Cassandra: `WHERE user_id = ? AND tweet_id < cursor.tweet_id LIMIT 20`

Cursors expire after 24 hours (stale feed sessions). If a cursor is expired, return the first page.

---

## Idempotency Strategy

| Endpoint | Idempotency Approach |
|---|---|
| POST /tweets | `Idempotency-Key` header; store key + response in Redis for 24h |
| POST /likes | Natural idempotency — like is idempotent if tweet_id + user_id already exists |
| POST /follows | Natural idempotency — follow is idempotent |
| POST /retweets | `Idempotency-Key` header; same as tweet creation |
| DELETE /tweets | Idempotent — deleting a deleted tweet returns 200 |

---

## Error Handling Standards

All errors return a consistent envelope:

```json
{
  "error": {
    "code": "TWEET_TOO_LONG",
    "message": "Tweet content exceeds 280 characters",
    "details": {
      "max_length": 280,
      "actual_length": 295
    },
    "request_id": "req-abc123",
    "timestamp": "2026-05-18T10:30:00Z"
  }
}
```

### HTTP Status Code Usage

| Code | Usage |
|---|---|
| 200 | Successful GET, idempotent PUT/DELETE |
| 201 | Resource created (POST) |
| 202 | Accepted — async processing (e.g., tweet deletion, large fanout) |
| 400 | Client error — validation failure |
| 401 | Missing or invalid auth token |
| 403 | Authenticated but forbidden (e.g., trying to delete another user's tweet) |
| 404 | Resource not found |
| 409 | Conflict (e.g., duplicate idempotency key with different payload) |
| 422 | Unprocessable entity (e.g., tweet content failed moderation check) |
| 429 | Rate limit exceeded |
| 503 | Service temporarily unavailable (circuit breaker open) |

---

## Rate Limiting

| Endpoint Group | Limit |
|---|---|
| Tweet creation | 300 per 15 minutes per user |
| Feed reads | 180 per 15 minutes per app token |
| Follow operations | 400 per day per user |
| Search | 450 per 15 minutes |
| Likes | 1,000 per day per user |

Rate limits return `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` headers on every response.

---

## API Evolution Strategy

1. **Additive changes are non-breaking**: Adding new fields to responses, adding new optional query params — always backward compatible.
2. **Breaking changes require a version bump**: Removing fields, changing field types, changing status codes.
3. **Field deprecation process**: Add `deprecated: true` annotation in OpenAPI spec. Add `X-Field-Deprecated: field_name` header. Maintain for 1 version lifecycle before removal.
4. **Feature flags for gradual rollout**: New API behavior can be gated behind a request header `X-Feature-Flag: new_ranking_v2` during A/B testing before becoming the default.

---

## Internal Service APIs (gRPC)

Internal service-to-service calls use gRPC for lower latency and typed contracts:

```
FeedService → FollowGraphService: GetFollowersBatch(user_id, cursor, limit)
FeedReadService → TweetHydrationService: HydrateTweets(tweet_ids[])
FanoutService → UserService: GetUserProfile(user_id)  ← to check celebrity status
```

---

## Interview-Level Discussion Points

1. **Cursor expiry and the "stale session" problem**: If a user leaves their feed open for 24+ hours and tries to scroll, their cursor is expired. Design for this gracefully — return fresh feed from the top with a `cursor_expired` indicator in the response.

2. **The `viewer_has_liked` field**: This is expensive — for each tweet in the feed, you must check if the requesting user has liked it. Batch this as a single Redis SMISMEMBER call against a `likes:{user_id}` set after fetching the feed, not as a per-tweet lookup.

3. **Why not GraphQL for the feed?**: HTTP caching (ETags, CDN edge caching) works naturally with REST but requires careful cache key design with GraphQL. Feed responses are highly structured and don't benefit from flexible field selection.

4. **Offline-first mobile clients**: Mobile apps should cache the last feed page locally. On reconnect, they request tweets newer than their last seen tweet_id. The API supports this via the cursor mechanism.

5. **Versioned endpoints in load balancer routing**: `/api/v1/` and `/api/v2/` can be routed to different service versions at the load balancer level, enabling gradual migration without application-level routing logic.
