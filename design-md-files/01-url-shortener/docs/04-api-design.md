# 04 — API Design: URL Shortener

---

## Objective

Define the REST API contract for the URL shortener service — including endpoint design, versioning strategy, pagination, error standards, idempotency, and API evolution.

---

## API Design Principles

- REST-first with JSON payloads
- Versioned via URL path (`/api/v1/`)
- Idempotent creation via client-provided idempotency keys
- Consistent error response structure across all endpoints
- Hypermedia links (HATEOAS) considered but deferred — adds complexity without immediate value at V1
- OpenAPI 3.0 spec maintained as the source of truth

---

## Base URL Structure

```
Production:  https://short.ly
Short links: https://short.ly/{shortCode}
API:         https://api.short.ly/v1/
```

Separating the API domain from the short link domain allows:
- Independent scaling of API vs. redirect traffic
- Separate CDN configurations
- Independent rate limiting policies

---

## Endpoints

### 1. URL Management

#### Create Short URL

```
POST /api/v1/urls

Headers:
  Authorization: Bearer <jwt>          (optional for anonymous)
  Idempotency-Key: <uuid>              (optional, client-generated)
  Content-Type: application/json

Request Body:
{
  "longUrl": "https://example.com/very/long/path?with=params",
  "alias": "my-product",              // optional
  "ttl": 86400,                        // optional, seconds; null = permanent
  "maxClicks": 1000,                   // optional
  "geoRules": [                        // optional
    { "country": "IN", "targetUrl": "https://example.in/page" },
    { "country": "US", "targetUrl": "https://example.com/page" }
  ],
  "tags": ["campaign-2024", "email"]  // optional, for organization
}

Response 201 Created:
{
  "shortUrl": "https://short.ly/my-product",
  "shortCode": "my-product",
  "longUrl": "https://example.com/very/long/path?with=params",
  "createdAt": "2024-01-15T10:30:00Z",
  "expiresAt": "2024-01-16T10:30:00Z",
  "analytics": {
    "dashboardUrl": "https://api.short.ly/v1/urls/my-product/analytics"
  },
  "_links": {
    "self": "/api/v1/urls/my-product",
    "delete": "/api/v1/urls/my-product",
    "analytics": "/api/v1/urls/my-product/analytics"
  }
}
```

**Idempotency behavior**: If `Idempotency-Key` matches a prior request within 24 hours, return the original 201 response. This prevents duplicate URL creation on network retries.

---

#### Get URL Metadata (not redirect)

```
GET /api/v1/urls/{shortCode}

Headers:
  Authorization: Bearer <jwt>    (required — only owner or admin)

Response 200 OK:
{
  "shortCode": "aB3xYz",
  "shortUrl": "https://short.ly/aB3xYz",
  "longUrl": "https://example.com/very/long/path",
  "status": "ACTIVE",            // ACTIVE | EXPIRED | DELETED
  "createdAt": "2024-01-15T10:30:00Z",
  "expiresAt": null,
  "ownerId": "usr_abc123",
  "clickCount": 42389,
  "tags": ["campaign-2024"]
}
```

---

#### Update URL

```
PATCH /api/v1/urls/{shortCode}

Headers:
  Authorization: Bearer <jwt>
  Content-Type: application/json

Request Body (partial update):
{
  "ttl": 3600,          // reset expiration
  "geoRules": [...],    // replace geo rules
  "tags": [...]         // replace tags
}

Response 200 OK: Updated URL metadata

Notes:
- longUrl cannot be changed after creation (immutable — security and trust)
- shortCode cannot be changed (immutable — it's the identity)
```

---

#### Delete URL

```
DELETE /api/v1/urls/{shortCode}

Headers:
  Authorization: Bearer <jwt>

Response 204 No Content

Notes:
- Soft delete only — marks status as DELETED
- Redis key evicted immediately (synchronous)
- Hard delete via scheduled cleanup after 30-day grace period
```

---

#### List My URLs

```
GET /api/v1/urls?page=0&size=20&status=ACTIVE&sort=createdAt,desc

Headers:
  Authorization: Bearer <jwt>

Query Parameters:
  page       int      (default: 0, cursor-based at V2)
  size       int      (default: 20, max: 100)
  status     string   (ACTIVE | EXPIRED | DELETED | ALL)
  tags       string[] (filter by tags, comma-separated)
  sort       string   (createdAt, clickCount — with direction)
  search     string   (search in alias/tags)

Response 200 OK:
{
  "content": [ ... array of URL metadata objects ... ],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 152,
    "totalPages": 8
  },
  "_links": {
    "self": "/api/v1/urls?page=0&size=20",
    "next": "/api/v1/urls?page=1&size=20",
    "prev": null
  }
}
```

---

### 2. Redirect Endpoint

```
GET /{shortCode}

No authentication required

Response variants:
  302 Found         → Location: <longUrl>               (normal redirect)
  301 Moved Permanently → Location: <longUrl>           (CDN-cacheable redirect)
  410 Gone          → {"error": "URL has expired"}      (expired URL)
  404 Not Found     → {"error": "Short URL not found"}  (invalid code)
  403 Forbidden     → {"error": "URL is blocked"}       (safety block)
  451 Unavailable For Legal Reasons → ...               (legal takedown)

Response Headers:
  X-Short-Code: aB3xYz
  X-Redirect-Type: GEO_ROUTED | DIRECT
  Cache-Control: no-store                              (for 302)
  Cache-Control: max-age=86400, public                (for 301)
```

**Why expose redirect at root path?** Short links must be as short as possible. `/r/aB3xYz` adds 3 characters — bad for aesthetics. The root path is the redirect path.

**Path conflict resolution**: Reserve `/api`, `/health`, `/metrics`, `/admin`, `/auth` as system paths. Reject aliases matching these reserved words.

---

### 3. Analytics Endpoints

#### URL Click Summary

```
GET /api/v1/urls/{shortCode}/analytics

Headers:
  Authorization: Bearer <jwt>

Query Parameters:
  from     ISO8601 datetime  (default: 7 days ago)
  to       ISO8601 datetime  (default: now)
  granularity  string        (HOUR | DAY | WEEK — default: DAY)

Response 200 OK:
{
  "shortCode": "aB3xYz",
  "period": { "from": "2024-01-08T00:00:00Z", "to": "2024-01-15T00:00:00Z" },
  "totalClicks": 42389,
  "uniqueClicks": 38201,         // by IP uniqueness (approximated)
  "botClicks": 1203,
  "timeSeries": [
    { "timestamp": "2024-01-08T00:00:00Z", "clicks": 5231, "uniqueClicks": 4801 },
    ...
  ],
  "byCountry": [
    { "country": "US", "clicks": 18000, "pct": 42.5 },
    { "country": "IN", "clicks": 12000, "pct": 28.3 },
    ...
  ],
  "byDevice": {
    "MOBILE": 28000,
    "DESKTOP": 12000,
    "TABLET": 2000,
    "BOT": 1203
  },
  "byReferrer": [
    { "referrer": "twitter.com", "clicks": 15000 },
    { "referrer": "direct", "clicks": 18000 }
  ]
}
```

---

#### Bulk URL Creation

```
POST /api/v1/urls/bulk

Headers:
  Authorization: Bearer <jwt>
  Idempotency-Key: <uuid>
  Content-Type: application/json

Request Body:
{
  "urls": [
    { "longUrl": "...", "alias": "...", "ttl": 3600 },
    { "longUrl": "...", "ttl": null },
    ...
  ]
}

Limits: max 100 URLs per bulk request (enforced)

Response 202 Accepted:
{
  "batchId": "batch_xyz",
  "status": "PROCESSING",
  "statusUrl": "/api/v1/urls/bulk/batch_xyz"
}

GET /api/v1/urls/bulk/{batchId}
Response 200 OK:
{
  "batchId": "batch_xyz",
  "status": "COMPLETED",
  "results": [
    { "index": 0, "shortCode": "aB3xYz", "status": "CREATED" },
    { "index": 1, "shortCode": null, "status": "FAILED", "error": "alias_conflict" }
  ]
}
```

**Why async?** 100 URL creations = 100 DB writes + 100 Redis writes. Synchronous would timeout under load. Async with polling avoids this.

---

## Error Response Standard

All errors follow this structure:

```json
{
  "error": {
    "code": "ALIAS_CONFLICT",
    "message": "The custom alias 'my-product' is already taken.",
    "field": "alias",
    "timestamp": "2024-01-15T10:30:00Z",
    "traceId": "abc-123-def-456",
    "documentation": "https://docs.short.ly/errors/ALIAS_CONFLICT"
  }
}
```

| HTTP Status | Error Code | Meaning |
|---|---|---|
| 400 | `INVALID_URL` | Long URL is not a valid URL |
| 400 | `INVALID_ALIAS` | Alias contains invalid characters |
| 400 | `RESERVED_ALIAS` | Alias conflicts with system path |
| 409 | `ALIAS_CONFLICT` | Custom alias already in use |
| 404 | `SHORT_URL_NOT_FOUND` | Short code doesn't exist |
| 410 | `SHORT_URL_EXPIRED` | URL existed but has expired |
| 422 | `URL_BLOCKED` | URL flagged by safety check |
| 429 | `RATE_LIMIT_EXCEEDED` | Too many requests |
| 401 | `AUTHENTICATION_REQUIRED` | JWT missing or invalid |
| 403 | `FORBIDDEN` | User doesn't own this URL |

---

## Versioning Strategy

| Strategy | Decision | Reason |
|---|---|---|
| URL path versioning (`/v1/`) | **Chosen** | Most explicit, easiest to route at gateway level |
| Header versioning (`API-Version: 1`) | Not used | Harder to discover, harder to route |
| Query param (`?version=1`) | Not used | Pollutes query string, caching issues |

**Version lifecycle:**
- `/v1/` — current stable
- `/v2/` — next version (when breaking changes required)
- V1 deprecated with 12-month sunset notice
- Deprecation signaled via `Deprecation: true` and `Sunset: <date>` response headers

---

## Pagination Strategy

**V1**: Offset-based pagination (simpler, sufficient at scale with proper indexes)

**V2**: Cursor-based pagination using `createdAt` + `shortCode` as composite cursor

| Approach | Pros | Cons |
|---|---|---|
| Offset-based | Simple, supports random page jumps | Inconsistent under inserts; slow at high offsets |
| Cursor-based | Consistent, fast | No random page access; cursor must be opaque |

At 10M+ URLs per user (enterprise), offset-based breaks down. Plan the cursor-based migration early.

---

## Idempotency Strategy

- Client sends `Idempotency-Key: <uuid>` header on POST requests
- Server stores `(idempotency_key, response_body, expires_at)` in Redis with 24-hour TTL
- On duplicate key detected → return cached response with `X-Idempotent-Replayed: true` header
- If original request is still in-flight → return 409 with `retry-after` header

This prevents double URL creation on retries (network timeout, client error).

---

## Rate Limiting Design

| User Tier | URL Creations/min | Redirects/min | Analytics Queries/min |
|---|---|---|---|
| Anonymous | 5 | 1000 | 0 |
| Free | 30 | 5000 | 10 |
| Pro | 300 | 50,000 | 100 |
| Enterprise | 3000 | 500,000 | 1000 |

Rate limit headers returned on every response:
```
X-RateLimit-Limit: 30
X-RateLimit-Remaining: 27
X-RateLimit-Reset: 1705318260
Retry-After: 15        (on 429 only)
```

---

## Interview Discussion Points

- **Why separate `GET /urls/{code}` (metadata) from `GET /{code}` (redirect)?** Different consumers — management UI vs. end user. Different auth requirements, different latency targets
- **Why not allow longUrl changes?** Security: if you can change where a URL points after it's been shared, it becomes a phishing vector. Immutability is a trust property
- **How do you handle very long long-URLs in the API?** 2048 character limit (HTTP spec advisory). Store in TEXT column, not VARCHAR
- **What happens on a bulk create if 50/100 fail?** Partial success — return results array with per-item status. The 50 successes are committed; failures can be retried individually
- **How do you version your Kafka event schema alongside the API?** Use Confluent Schema Registry with Avro — schema evolution rules (backward/forward compatibility) enforced at the registry level
