# 04 — API Design: File Storage System

## Objective
Define the complete REST API surface for the file storage platform — covering upload, download, metadata management, sharing, versioning, and sync. Every endpoint decision must account for resumability, idempotency, backward compatibility, and large file handling.

---

## API Versioning Strategy

- **URL path versioning**: `/api/v1/files`, `/api/v2/files` — explicit, cacheable, easy to route.
- New versions introduced only for breaking changes. Additive changes (new fields, new query params) are non-breaking and shipped in the same version.
- Old versions supported for minimum 18 months after new version release.
- Version sunset communicated via `Sunset` response header 6 months before deprecation.
- **Never breaking**: adding optional fields to request/response, adding new endpoints, adding new enum values that clients must ignore.

---

## Authentication & Authorization

All endpoints require `Authorization: Bearer {jwt}` header except:
- `GET /api/v1/files/{fileId}/download?token={publicLinkToken}` — public link access.
- `/api/v1/auth/*` — login/register endpoints.

---

## Upload API

### 1. Initialize Upload Session
```
POST /api/v1/uploads/init
```

**Request Body**:
```json
{
  "fileName": "quarterly-report.pdf",
  "mimeType": "application/pdf",
  "totalSizeBytes": 52428800,
  "totalChunks": 10,
  "chunkSizeBytes": 5242880,
  "fileHash": "sha256:abc123...",
  "parentFolderId": "folder-uuid-here"
}
```

**Response** `201 Created`:
```json
{
  "uploadId": "upload-uuid",
  "expiresAt": "2024-01-15T12:00:00Z",
  "chunks": [
    { "chunkIndex": 0, "presignedUrl": "https://s3.../...", "expiresAt": "..." },
    { "chunkIndex": 1, "presignedUrl": "https://s3.../...", "expiresAt": "..." }
  ]
}
```

**Design decisions**:
- Client declares total size and chunk count upfront → server can validate quota before upload begins.
- Presigned URLs expire in 15 minutes — client must request refresh for stalled uploads.
- `fileHash` (whole-file SHA-256) sent upfront for deduplication check. If hash known → return existing `fileId` immediately without upload.

---

### 2. Check Chunk Deduplication (Optional — Client Optimization)
```
POST /api/v1/uploads/{uploadId}/chunks/dedupe-check
```

**Request Body**:
```json
{
  "chunks": [
    { "chunkIndex": 0, "chunkHash": "sha256:def456..." },
    { "chunkIndex": 1, "chunkHash": "sha256:ghi789..." }
  ]
}
```

**Response** `200 OK`:
```json
{
  "results": [
    { "chunkIndex": 0, "needsUpload": false },
    { "chunkIndex": 1, "needsUpload": true, "presignedUrl": "..." }
  ]
}
```

Client skips uploading chunks where `needsUpload: false` — they already exist in storage.

---

### 3. Complete Upload Session
```
POST /api/v1/uploads/{uploadId}/complete
```

**Request Body**:
```json
{
  "chunkManifest": [
    { "chunkIndex": 0, "etag": "etag-from-s3-0" },
    { "chunkIndex": 1, "etag": "etag-from-s3-1" }
  ]
}
```

**Response** `202 Accepted` (async — file processing continues):
```json
{
  "fileId": "file-uuid",
  "status": "PROCESSING",
  "pollUrl": "/api/v1/files/file-uuid/status"
}
```

**Idempotency**: If called twice with the same `uploadId` → returns the same `fileId`. Safe to retry.

---

### 4. Get Upload Status
```
GET /api/v1/uploads/{uploadId}/status
```

**Response**:
```json
{
  "uploadId": "upload-uuid",
  "status": "COMPLETING | COMPLETE | FAILED",
  "fileId": "file-uuid",
  "errorMessage": null
}
```

---

## File Management API

### 5. Get File Metadata
```
GET /api/v1/files/{fileId}
```

**Response** `200 OK`:
```json
{
  "fileId": "file-uuid",
  "name": "quarterly-report.pdf",
  "mimeType": "application/pdf",
  "sizeBytes": 52428800,
  "currentVersion": 3,
  "parentFolderId": "folder-uuid",
  "ownerId": "user-uuid",
  "uploadedAt": "2024-01-10T08:00:00Z",
  "lastModifiedAt": "2024-01-14T16:30:00Z",
  "previewUrl": "https://cdn.example.com/preview/file-uuid/thumb.jpg",
  "downloadUrl": "/api/v1/files/file-uuid/download",
  "sharing": {
    "isShared": true,
    "shareCount": 3
  }
}
```

---

### 6. Download File
```
GET /api/v1/files/{fileId}/download
GET /api/v1/files/{fileId}/download?version={versionNumber}
GET /api/v1/files/{fileId}/download?token={publicLinkToken}
```

**Response**: `302 Redirect` to presigned S3 URL (15-min TTL).

**Design decisions**:
- Redirect rather than streaming through app servers (avoids bandwidth bottleneck).
- `version` query param allows downloading historical versions.
- `token` param allows unauthenticated access via public share link (still validates link active, not expired/revoked).

---

### 7. Update File (Rename)
```
PATCH /api/v1/files/{fileId}
```

**Request Body**:
```json
{ "name": "Q3-report-final.pdf" }
```

**Response** `200 OK` with updated file metadata.

---

### 8. Move File
```
POST /api/v1/files/{fileId}/move
```

**Request Body**:
```json
{ "targetFolderId": "new-folder-uuid" }
```

**Idempotency header**: `Idempotency-Key: client-generated-uuid` — safe to retry if network drops.

---

### 9. Trash File
```
DELETE /api/v1/files/{fileId}
```

**Response** `204 No Content`. Sets `status = TRASHED`. Reversible for 30 days.

---

### 10. Restore from Trash
```
POST /api/v1/files/{fileId}/restore
```

---

### 11. Permanently Delete
```
DELETE /api/v1/files/{fileId}/permanent
```

Triggers async cleanup pipeline. Returns `202 Accepted`.

---

## Folder API

### 12. Create Folder
```
POST /api/v1/folders
```

**Request Body**:
```json
{
  "name": "Q3 Reports",
  "parentFolderId": "parent-folder-uuid"
}
```

---

### 13. List Folder Contents
```
GET /api/v1/folders/{folderId}/contents
```

**Query Parameters**:
- `page` (default: 1), `pageSize` (default: 50, max: 200)
- `sortBy` (name | lastModified | size), `order` (asc | desc)
- `type` (file | folder | all)

**Response** `200 OK`:
```json
{
  "folderId": "folder-uuid",
  "folderName": "Q3 Reports",
  "totalItems": 142,
  "page": 1,
  "pageSize": 50,
  "items": [
    { "type": "folder", "folderId": "...", "name": "Finance", "itemCount": 23 },
    { "type": "file", "fileId": "...", "name": "report.pdf", "sizeBytes": 5242880 }
  ]
}
```

**Pagination**: cursor-based for large folders (`nextCursor: "opaque-string"`) preferred over offset for consistency during concurrent modifications.

---

## Versioning API

### 14. List File Versions
```
GET /api/v1/files/{fileId}/versions
```

**Response**: paginated list of FileVersion objects with `versionNumber`, `sizeBytes`, `contentHash`, `uploadedAt`.

---

### 15. Restore Version
```
POST /api/v1/files/{fileId}/versions/{versionNumber}/restore
```

Creates a new version that is a copy of the specified version's content. The file's `currentVersionId` is updated to the new version. Original version history is preserved.

---

## Sharing API

### 16. Create Share
```
POST /api/v1/files/{fileId}/shares
POST /api/v1/folders/{folderId}/shares
```

**Request Body**:
```json
{
  "shareType": "USER",
  "granteeEmail": "colleague@example.com",
  "permission": "EDIT",
  "message": "Please review before Monday"
}
```

Or for public link:
```json
{
  "shareType": "PUBLIC_LINK",
  "permission": "VIEW",
  "expiresAt": "2024-02-01T00:00:00Z"
}
```

**Response** `201 Created`:
```json
{
  "shareId": "share-uuid",
  "shareType": "PUBLIC_LINK",
  "linkUrl": "https://files.example.com/s/token-abc123",
  "permission": "VIEW",
  "expiresAt": "2024-02-01T00:00:00Z"
}
```

---

### 17. Update Share Permission
```
PATCH /api/v1/shares/{shareId}
```

---

### 18. Revoke Share
```
DELETE /api/v1/shares/{shareId}
```

---

## Search API

### 19. Search Files
```
GET /api/v1/search?q={query}&type={file|folder}&mimeType={pdf|image|...}&page=1&pageSize=20
```

**Response**:
```json
{
  "query": "Q3 report",
  "totalResults": 14,
  "results": [
    {
      "fileId": "...",
      "name": "Q3-Report.pdf",
      "parentFolder": "Finance/2024",
      "highlight": "...Q3 <em>report</em> summary...",
      "lastModifiedAt": "..."
    }
  ]
}
```

---

## Sync API

### 20. Get Changes Since Cursor
```
GET /api/v1/sync/changes?since={cursor}&deviceId={deviceId}
```

**Response**:
```json
{
  "changes": [
    { "type": "CREATED", "fileId": "...", "name": "new-doc.pdf", "storageKey": "..." },
    { "type": "MODIFIED", "fileId": "...", "currentVersion": 4 },
    { "type": "DELETED", "fileId": "..." }
  ],
  "nextCursor": "opaque-cursor-value",
  "hasMore": false
}
```

Cursor is opaque (encodes Kafka offset or event sequence number). Client stores cursor locally, sends on next poll.

---

## Error Response Format

All errors follow RFC 7807 (Problem Details):
```json
{
  "type": "https://api.example.com/errors/quota-exceeded",
  "title": "Storage quota exceeded",
  "status": 422,
  "detail": "Upload of 52 MB would exceed your 15 GB quota. Current usage: 14.8 GB.",
  "requestId": "req-abc123",
  "traceId": "trace-def456"
}
```

### Standard Error Codes

| HTTP Status | Scenario |
|-------------|----------|
| 400 | Invalid request body (missing fields, wrong types) |
| 401 | Missing or expired JWT |
| 403 | Permission denied (file exists but user has no access) |
| 404 | File/folder not found (or access denied — ambiguous by design for security) |
| 409 | Conflict (file with same name already exists in folder) |
| 413 | File size exceeds limit (5 GB) |
| 422 | Quota exceeded, validation failure |
| 429 | Rate limit exceeded |
| 503 | Service temporarily unavailable |

---

## Rate Limiting

| Endpoint | Limit |
|----------|-------|
| Upload init | 10 sessions/min per user |
| Download | 1000 requests/min per user (CDN handles most) |
| Search | 60 requests/min per user |
| Share create | 50 creates/hour per user |
| API global | 500 requests/min per user |

Rate limit response headers: `X-RateLimit-Remaining`, `X-RateLimit-Reset`.

---

## Interview-Level Discussion Points

- **Why 302 redirect for download instead of streaming?** — Streaming through app servers creates bandwidth bottleneck. At 12,000 download RPS × 5 MB = 60 GB/s egress — no app server fleet can handle this. Presigned URL to S3 offloads all transfer. CDN caches the redirect target.
- **Why async upload completion?** — Object storage multipart completion can take 1–5 seconds for large files. Holding the HTTP connection open is wasteful. `202 Accepted` + poll URL is the correct pattern.
- **How do you handle idempotency on move?** — Client generates UUID `Idempotency-Key`. Server stores `{key: fileId+targetFolderId}` in Redis (TTL 24h). If request replayed → returns stored result. No duplicate moves.
- **Why cursor-based sync instead of timestamp?** — Timestamps are susceptible to clock skew across servers. Cursor (Kafka offset or DB sequence) is strictly ordered and unambiguous. "Give me all changes after offset 12345" is reliable. "Give me all changes after 2024-01-10T12:00:00Z" has clock drift and replication lag issues.
