# 04 — API Design

## Objective
Define the REST and WebSocket APIs for the Collaborative Document Editor. Establish versioning, pagination, idempotency, and error standards. Cover the real-time protocol design and offline sync API.

---

## API Topology Overview

| API Surface | Protocol | Use Case |
|---|---|---|
| Document CRUD & metadata | REST over HTTPS | Document lifecycle, permissions, export |
| Real-time collaboration | WebSocket (WSS) | Live op delivery, presence, cursor sync |
| Full-text search | REST over HTTPS | Document search |
| GraphQL (optional) | GraphQL over HTTPS | Flexible client queries (dashboard, mobile) |
| Internal service-to-service | gRPC | Permission check, snapshot fetch |

---

## REST API Design

### Base URL
```
https://api.docs.example.com/v1
```

### Versioning Strategy
- URL-path versioning: `/v1/`, `/v2/`
- Header versioning not used (harder to cache, harder to route)
- Support N-1 versions simultaneously (v1 supported after v2 launch)
- Deprecation: 6-month notice via `Deprecation` response header; sunset date in `Sunset` header

---

### Document Endpoints

#### Create Document
```
POST /v1/documents
Authorization: Bearer {jwt}
Content-Type: application/json

Request:
{
  "title": "Q4 Planning",
  "workspaceId": "ws_abc123",
  "templateId": "tmpl_blank"   // optional
}

Response 201:
{
  "documentId": "doc_xyz789",
  "title": "Q4 Planning",
  "ownerId": "usr_alice",
  "workspaceId": "ws_abc123",
  "createdAt": "2026-05-18T10:00:00Z",
  "shareUrl": "https://docs.example.com/doc_xyz789",
  "permissions": { "self": "owner" }
}
```

#### Get Document (with content)
```
GET /v1/documents/{documentId}?format=delta&seq=latest
Authorization: Bearer {jwt}

Response 200:
{
  "documentId": "doc_xyz789",
  "title": "Q4 Planning",
  "snapshotSeq": 1042,
  "content": { "ops": [...] },       // Delta format
  "pendingOps": [],                  // Ops after snapshot not yet in snapshot
  "updatedAt": "2026-05-18T11:30:00Z",
  "collaborators": ["usr_alice", "usr_bob"]
}
```

**Idempotency:** GET is naturally idempotent.

#### Update Document Metadata
```
PATCH /v1/documents/{documentId}
Idempotency-Key: {uuid}

Request:
{
  "title": "Q4 Planning — Final"
}
```

#### Delete (Soft)
```
DELETE /v1/documents/{documentId}
```
Document is soft-deleted; content retained for 30 days for recovery.

---

### Version History Endpoints

#### List Versions
```
GET /v1/documents/{documentId}/versions?limit=20&cursor={cursorToken}

Response 200:
{
  "versions": [
    {
      "versionId": "ver_001",
      "seq": 1042,
      "name": "After legal review",
      "createdBy": "usr_alice",
      "createdAt": "2026-05-18T09:00:00Z",
      "isAutoSave": false
    }
  ],
  "nextCursor": "eyJzZXEiOiA5NDB9"
}
```

**Pagination**: Cursor-based (opaque base64 cursor encoding `{seq, timestamp}`). Offset-based pagination is not used because the version list is append-only and cursor pagination is stable.

#### Get Document at Version
```
GET /v1/documents/{documentId}/versions/{versionId}/content
```

#### Restore to Version
```
POST /v1/documents/{documentId}/versions/{versionId}/restore
Idempotency-Key: {uuid}
```
Restores by creating a new op that replaces current content with the version's snapshot. Does not rewrite history.

---

### Permission & Sharing Endpoints

#### Get Permissions
```
GET /v1/documents/{documentId}/permissions
```

#### Grant Permission
```
POST /v1/documents/{documentId}/permissions
Idempotency-Key: {uuid}

Request:
{
  "principalType": "user",          // user | group | link
  "principalId": "usr_bob",
  "accessLevel": "editor",
  "expiresAt": null
}

Response 201:
{
  "permissionId": "perm_abc",
  "shareLink": null
}
```

#### Create Share Link
```
POST /v1/documents/{documentId}/permissions
{
  "principalType": "link",
  "accessLevel": "viewer",
  "expiresAt": "2026-06-18T00:00:00Z",
  "requirePassword": false
}

Response 201:
{
  "permissionId": "perm_lnk_xyz",
  "shareLink": "https://docs.example.com/d/TOKEN",
  "expiresAt": "2026-06-18T00:00:00Z"
}
```

---

### Export Endpoints
```
POST /v1/documents/{documentId}/exports
{
  "format": "pdf",              // pdf | docx | markdown | html
  "versionId": null             // null = current version
}

Response 202 Accepted:
{
  "exportJobId": "job_abc123",
  "statusUrl": "/v1/exports/job_abc123"
}

GET /v1/exports/{jobId}
Response 200:
{
  "status": "completed",
  "downloadUrl": "https://storage.example.com/exports/job_abc123.pdf?expires=..."
  "expiresAt": "2026-05-18T12:00:00Z"
}
```

---

## WebSocket Protocol Design

### Connection Handshake
```
WSS wss://collab.docs.example.com/v1/documents/{documentId}
Headers:
  Authorization: Bearer {jwt}
  X-Client-Seq: {lastKnownSeq}   // for reconnect recovery
```

### Message Envelope (JSON)
```json
{
  "type": "<message_type>",
  "msgId": "<client-generated UUID>",
  "payload": { ... }
}
```

### Message Types (Client → Server)

| Type | Purpose | Payload |
|---|---|---|
| `op` | Submit an edit operation | `{clientSeq, parentSeq, operation: {...}}` |
| `cursor` | Update cursor/selection | `{position, selection}` |
| `ping` | Heartbeat | `{}` |
| `undo` | Request undo | `{clientSeq}` |
| `redo` | Request redo | `{clientSeq}` |

### Message Types (Server → Client)

| Type | Purpose | Payload |
|---|---|---|
| `ack` | Operation acknowledged | `{clientSeq, serverSeq}` |
| `op` | Broadcast op from another user | `{serverSeq, authorId, operation}` |
| `presence` | Cursor update from another user | `{userId, cursor, selection, color}` |
| `presence_leave` | User left session | `{userId}` |
| `error` | Op rejected | `{clientSeq, code, reason}` |
| `sync` | Force client to re-sync | `{fromSeq}` — sent when client's parent seq is too old |
| `pong` | Heartbeat response | `{}` |

### Operation Acknowledgment Contract
- Client sends op with `clientSeq` (monotonically increasing per client)
- Server transforms and applies op; responds with `ack {clientSeq, serverSeq}`
- Client buffers all sent ops until ack received; retransmits on reconnect
- If `sync` received, client fetches ops from server since `fromSeq` and re-applies local buffer

### Reconnect & Recovery Protocol
1. Client disconnects; buffers unsent/unacked ops locally
2. On reconnect, sends `X-Client-Seq` header with last acked server seq
3. Server sends all ops from `(lastAckedSeq, now]` as catch-up
4. Client re-sends buffered ops against updated server state
5. Server transforms buffered ops; applies or rejects conflicts

---

## Internal gRPC APIs

### Permission Service
```
service PermissionService {
  rpc CheckPermission(CheckRequest) returns (CheckResponse);
  rpc GetEffectivePermissions(GetPermissionsRequest) returns (GetPermissionsResponse);
}

message CheckRequest {
  string principal_id = 1;
  string document_id = 2;
  string required_level = 3;  // "viewer" | "commenter" | "editor" | "owner"
}

message CheckResponse {
  bool allowed = 1;
  string effective_level = 2;
}
```

### Snapshot Service
```
service SnapshotService {
  rpc GetLatestSnapshot(GetSnapshotRequest) returns (Snapshot);
  rpc GetSnapshotAtSeq(GetSnapshotAtSeqRequest) returns (Snapshot);
}
```

---

## Error Handling Standards

### Error Response Format
```json
{
  "error": {
    "code": "PERMISSION_DENIED",
    "message": "You do not have edit access to this document",
    "requestId": "req_abc123",
    "details": {}
  }
}
```

### HTTP Error Codes

| Scenario | HTTP Code | Error Code |
|---|---|---|
| Invalid JWT | 401 | `UNAUTHENTICATED` |
| No permission | 403 | `PERMISSION_DENIED` |
| Document not found | 404 | `NOT_FOUND` |
| Op conflict (OT failed) | 409 | `OP_CONFLICT` |
| Op out of order | 422 | `OP_SEQUENCE_ERROR` |
| Rate limit exceeded | 429 | `RATE_LIMITED` |
| Collaboration service down | 503 | `SERVICE_UNAVAILABLE` |

---

## Idempotency Strategy

- All state-mutating REST endpoints (POST, PATCH, DELETE) accept `Idempotency-Key: {uuid}` header
- Server stores result of first execution in Redis with key `idempotency:{clientId}:{uuid}` for 24 hours
- Duplicate requests return the cached response without re-executing
- WebSocket ops are idempotent by `clientSeq` — duplicate clientSeq from same session is ignored

---

## Rate Limiting

| Tier | Limit | Window |
|---|---|---|
| Document read | 1,000 req/min per user | Sliding window |
| Document create | 100 req/hour per user | Fixed window |
| Op submission (WS) | 100 ops/sec per session | Token bucket |
| Export | 10 exports/hour per user | Fixed window |
| Share link creation | 20/hour per user | Fixed window |
| Unauthenticated view | 60 req/min per IP | Sliding window |

---

## API Evolution & Backward Compatibility
- New optional fields added to responses without version bump
- Removing or renaming fields requires a new version
- WebSocket protocol: new message types are additive; clients ignore unknown types
- Op schema: Avro with Schema Registry; backward-compatible evolution only

---

## Interview Discussion Points
- Why cursor-based pagination over offset for version history, and what are the edge cases when the list is mutated during pagination?
- How does the WebSocket reconnect protocol handle the case where the client's `lastAckedSeq` is older than the Kafka retention window?
- Why is idempotency at the WebSocket level different from idempotency at the REST level?
- How would you design the API differently for mobile clients with poor connectivity vs desktop clients?
- What is the security risk of exposing `parentSeq` in operation messages, and how do you mitigate it?
