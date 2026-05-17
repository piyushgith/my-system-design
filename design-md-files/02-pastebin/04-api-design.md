# 04 — API Design: Pastebin / Code Sharing Platform

---

## Objective

Design a clean, production-grade REST API for the Pastebin platform. Cover versioning, pagination, idempotency, error standards, authentication, and rate limiting. Think about API evolution and backward compatibility.

---

## API Design Principles

1. **Resource-oriented**: URLs represent resources (pastes, users), not actions
2. **Stateless**: No server-side session; auth via JWT Bearer or API Key
3. **Versioned**: API version in URL path (`/api/v1/`) — not headers (more discoverable)
4. **Idempotent writes**: Paste creation supports idempotency keys
5. **Consistent errors**: All errors use RFC 7807 Problem Detail format
6. **Pagination**: Cursor-based for list endpoints (not offset-based at scale)

---

## Versioning Strategy

### Why URL path versioning?

```
/api/v1/pastes    ← Current
/api/v2/pastes    ← Future breaking change
```

- Most CDN and proxy configurations understand URL-based routing
- Easy to test via browser / curl without custom headers
- Clear and explicit — clients know exactly which version they're using

**Alternative considered:** Header-based versioning (`Accept: application/vnd.pastebin.v1+json`)
- Pro: Cleaner URLs
- Con: Harder to test, CDN caching issues, less discoverable
- **Rejected** for Pastebin (client simplicity matters — many users are developers using curl)

### Backward Compatibility Commitment
- `/api/v1/` maintained for 12 months after `/api/v2/` release
- Breaking changes (removed fields, changed types, changed status codes) always require a new version
- Additive changes (new optional fields, new optional query params) are non-breaking and deployed to existing version

---

## Authentication

| Mechanism | Use Case |
|-----------|----------|
| JWT Bearer Token | Web users (issued via login/OAuth) |
| API Key (Header: `X-Api-Key`) | Programmatic/CI-CD access |
| Anonymous | Public paste creation, public paste reads |

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
X-Api-Key: pb_live_abc123xyz789...
```

**JWT Claims:**
```json
{
  "sub": "usr_abc123",
  "email": "user@example.com",
  "roles": ["user"],
  "exp": 1716000000,
  "iat": 1715913600
}
```

**API Key format:** `pb_{env}_{random_32_chars}` — prefix helps identify key type in logs.

---

## Rate Limiting

| Client Type | Endpoint | Limit |
|-------------|---------|-------|
| Anonymous | POST /pastes | 10/hour per IP |
| Anonymous | GET /pastes/* | 100/minute per IP |
| Authenticated User | POST /pastes | 100/day |
| Authenticated User | GET /pastes/* | Unlimited |
| API Key | POST /pastes | 1000/day (configurable) |

**Rate limit headers returned on every response:**
```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 87
X-RateLimit-Reset: 1716000060
Retry-After: 60   (only on 429)
```

---

## Error Format (RFC 7807 Problem Detail)

```json
{
  "type": "https://api.pastebin.io/problems/paste-not-found",
  "title": "Paste Not Found",
  "status": 404,
  "detail": "No paste found with key 'abc123'",
  "instance": "/api/v1/pastes/abc123",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

**Standard error codes:**

| HTTP Status | Meaning |
|-------------|---------|
| 400 | Bad Request — validation failure |
| 401 | Unauthorized — missing or invalid auth |
| 403 | Forbidden — authenticated but not allowed |
| 404 | Not Found — paste does not exist or is deleted |
| 409 | Conflict — custom alias already taken |
| 410 | Gone — paste existed but has expired/been deleted |
| 413 | Payload Too Large — content exceeds 10 MB |
| 422 | Unprocessable — business rule violation |
| 429 | Too Many Requests — rate limit exceeded |
| 500 | Internal Server Error |
| 503 | Service Unavailable — downstream dependency down |

**Important design note:** Expired pastes return `410 Gone`, NOT `404`. This is semantically important — the resource existed but is gone. CDN should cache 410 responses to avoid repeated origin hits for expired paste keys.

---

## API Endpoints

### 1. Create Paste

```
POST /api/v1/pastes
```

**Headers:**
```
Authorization: Bearer {token}   (optional)
Idempotency-Key: {uuid}         (optional, for deduplication)
Content-Type: application/json
```

**Request Body:**
```json
{
  "title": "My Spring Boot Config",
  "content": "server:\n  port: 8080\n  ...",
  "language": "yaml",
  "expiryPolicy": "ONE_DAY",
  "accessLevel": "PUBLIC",
  "password": null,
  "customAlias": null
}
```

**Field Rules:**
| Field | Required | Default | Constraints |
|-------|----------|---------|-------------|
| content | Yes | - | 1 byte to 10 MB |
| language | No | "plaintext" | Must be in supported list |
| expiryPolicy | No | "ONE_WEEK" | ONE_HOUR, ONE_DAY, ONE_WEEK, ONE_MONTH, NEVER |
| accessLevel | No | "PUBLIC" | PUBLIC, UNLISTED, PRIVATE |
| password | No | null | Max 72 chars (bcrypt limit) |
| customAlias | No | null | 4-32 chars, alphanumeric + dash, authenticated users only |
| title | No | null | Max 255 chars |

**Response: 201 Created**
```json
{
  "id": "paste_abc123",
  "shortKey": "abc123",
  "url": "https://pastebin.io/p/abc123",
  "rawUrl": "https://pastebin.io/raw/abc123",
  "language": "yaml",
  "expiresAt": "2026-05-18T12:00:00Z",
  "accessLevel": "PUBLIC",
  "createdAt": "2026-05-17T12:00:00Z",
  "size": 1024
}
```

**Idempotency behavior:** If same `Idempotency-Key` is submitted within 24 hours, return the original response (200, not 201) — no duplicate paste created.

---

### 2. Get Paste (Metadata + Content)

```
GET /api/v1/pastes/{key}
```

**Headers:**
```
Authorization: Bearer {token}   (for private pastes)
X-Paste-Password: {password}    (for password-protected pastes)
```

**Response: 200 OK**
```json
{
  "id": "paste_abc123",
  "shortKey": "abc123",
  "title": "My Spring Boot Config",
  "content": "server:\n  port: 8080\n  ...",
  "language": "yaml",
  "expiresAt": "2026-05-18T12:00:00Z",
  "accessLevel": "PUBLIC",
  "viewCount": 42,
  "size": 1024,
  "createdAt": "2026-05-17T12:00:00Z",
  "owner": {
    "id": "usr_xyz",
    "displayName": "piyush"
  }
}
```

**For large pastes (> 1 MB):**
```json
{
  "id": "paste_abc123",
  "shortKey": "abc123",
  "language": "java",
  "size": 5242880,
  "contentUrl": "/api/v1/pastes/abc123/content",
  "expiresAt": "...",
  "createdAt": "..."
}
```
Large pastes return a `contentUrl` — client fetches content separately (streaming download).

---

### 3. Get Raw Paste Content

```
GET /raw/{key}
```

**Response: 200 OK**
```
Content-Type: text/plain; charset=utf-8
Cache-Control: public, max-age=3600
Content-Disposition: inline; filename="abc123.yaml"

server:
  port: 8080
  ...
```

**Notes:**
- This endpoint is served directly from CDN for public pastes
- No JSON wrapper — plain text only
- `Cache-Control` based on expiry: if expires in 1 day, `max-age=86400`
- For NEVER expiry: `Cache-Control: public, max-age=604800, s-maxage=2592000`

---

### 4. Delete Paste

```
DELETE /api/v1/pastes/{key}
```

**Headers:**
```
Authorization: Bearer {token}   (required — owner only)
```

**Response: 204 No Content**

**Idempotent:** If paste is already deleted, returns 204 (not 404) — callers can safely retry.

---

### 5. Fork Paste

```
POST /api/v1/pastes/{key}/fork
```

**Request Body (optional overrides):**
```json
{
  "title": "My fork of abc123",
  "accessLevel": "PRIVATE",
  "expiryPolicy": "ONE_WEEK"
}
```

**Response: 201 Created** (same schema as Create Paste)

---

### 6. List User Pastes

```
GET /api/v1/users/me/pastes
```

**Headers:**
```
Authorization: Bearer {token}   (required)
```

**Query Parameters:**
| Param | Default | Description |
|-------|---------|-------------|
| cursor | null | Pagination cursor (opaque string) |
| limit | 20 | Max items (1-100) |
| language | null | Filter by language |
| accessLevel | null | Filter by access level |
| includeExpired | false | Include expired pastes |

**Response: 200 OK**
```json
{
  "items": [
    {
      "shortKey": "abc123",
      "title": "My Config",
      "language": "yaml",
      "accessLevel": "PUBLIC",
      "viewCount": 42,
      "size": 1024,
      "expiresAt": "2026-05-18T12:00:00Z",
      "createdAt": "2026-05-17T12:00:00Z"
    }
  ],
  "cursor": "eyJjcmVhdGVkQXQiOiIyMDI2LTA1LTE3VDEyOjAwOjAwWiIsImlkIjoiYWJjMTIzIn0=",
  "hasMore": true
}
```

**Pagination strategy — why cursor-based?**
- Offset pagination (`?page=5`) breaks when items are inserted/deleted between pages
- Cursor is encoded as `{createdAt, id}` sorted descending — stable across mutations
- Cursor is Base64-encoded JSON (opaque to client, not meant to be parsed)

---

### 7. Get Supported Languages

```
GET /api/v1/meta/languages
```

**Response: 200 OK**
```json
{
  "languages": [
    { "id": "java", "label": "Java" },
    { "id": "python", "label": "Python" },
    { "id": "yaml", "label": "YAML" },
    { "id": "plaintext", "label": "Plain Text" }
  ]
}
```

---

### 8. Report Abuse

```
POST /api/v1/pastes/{key}/report
```

**Request Body:**
```json
{
  "reason": "MALWARE",
  "description": "This paste contains obfuscated malware"
}
```

**Reasons:** `MALWARE`, `SPAM`, `ILLEGAL_CONTENT`, `COPYRIGHT`, `OTHER`

**Response: 202 Accepted** (not 200 — processing is async)

---

## Idempotency Design

Paste creation supports idempotency via the `Idempotency-Key` header:

1. Client sends `Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000`
2. Server checks Redis: `SET idempotency:{key} {response} NX EX 86400`
3. If key already exists: return stored response (200, not 201)
4. If key is new: process normally, store response, return 201

**Why Redis for idempotency?**
- Fast atomic check-and-set (SET NX)
- TTL-based automatic expiration (24 hours)
- Acceptable to lose keys on Redis restart (idempotency is a best-effort guarantee, not a durability requirement)

---

## API Pagination: Cursor Encoding

```
Cursor = Base64( JSON { "id": "paste_abc123", "createdAt": "2026-05-17T12:00:00Z" } )
```

Query executed:
```sql
SELECT * FROM pastes
WHERE owner_id = ?
  AND (created_at < ? OR (created_at = ? AND id < ?))
ORDER BY created_at DESC, id DESC
LIMIT ?
```

This composite cursor ensures stable pagination even when a paste is inserted with the same timestamp.

---

## API Evolution Strategy

### Non-breaking changes (no version bump needed)
- Adding new optional request fields (with sensible defaults)
- Adding new response fields
- Adding new query parameters
- Adding new endpoints

### Breaking changes (require new version)
- Removing or renaming fields
- Changing field types
- Changing HTTP status codes
- Changing authentication mechanism
- Removing endpoints

### Sunset strategy
- Add `Sunset` header to deprecated endpoints: `Sunset: Sat, 01 May 2027 00:00:00 GMT`
- Add `Deprecation` header: `Deprecation: Mon, 01 Jan 2027 00:00:00 GMT`
- Send email notifications to API key holders using deprecated endpoints

---

## Interview Discussion Points

- Why 410 Gone for expired pastes instead of 404?
- How does cursor-based pagination prevent the "page skip" problem?
- What is the maximum safe size for an API response before you should stream it?
- How do you handle the case where two clients create pastes with the same custom alias simultaneously?
- Should the raw content endpoint be on the same domain as the API? (Security: CDN domain separation prevents cookie theft)
- How would you version the API if you needed to change the short key format (e.g., from 6 to 8 chars)?
