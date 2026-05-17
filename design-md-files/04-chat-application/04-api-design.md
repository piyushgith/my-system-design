# 04 — API Design: Chat Application

---

## Objective

Design the REST and WebSocket APIs for the chat platform. Cover message send/receive, conversation management, presence, and synchronization APIs. Include versioning strategy, pagination, idempotency, error handling, and backward compatibility.

---

## API Surface Overview

| API Type | Transport | Use Case |
|----------|-----------|----------|
| REST over HTTPS | HTTP/1.1 or HTTP/2 | Conversation management, history, media, search |
| WebSocket (WSS) | WS over TLS | Real-time message send/receive, presence, typing |
| gRPC | HTTP/2 | Internal service-to-service (not client-facing) |

Clients open a persistent WebSocket for real-time events. All non-real-time operations use REST.

---

## API Versioning Strategy

- URL path versioning: `/api/v1/conversations`, `/api/v2/conversations`
- Version at the API Gateway level — internal services are not versioned
- Minimum 12-month deprecation period before removing a version
- Version negotiation: client sends `X-API-Version: 2` header as fallback for older SDKs
- Breaking changes (field removal, semantic change) → bump to `/v2`
- Additive changes (new optional fields) → backward compatible, no version bump

---

## Authentication

All REST API calls require:
```
Authorization: Bearer <JWT>
```

The JWT payload contains:
```json
{
  "sub": "user_id",
  "device_id": "device_id",
  "iat": 1700000000,
  "exp": 1700086400
}
```

WebSocket connection requires JWT as query parameter or first message after connect:
```
wss://chat.example.com/ws?token=<JWT>
```

The JWT is validated at the API Gateway. WebSocket servers trust the `user_id` claim without re-calling Identity Service.

---

## REST API: Conversations

### Create Conversation
```
POST /api/v1/conversations
```
Request:
```json
{
  "type": "GROUP",
  "name": "Project Alpha",
  "member_ids": ["user-001", "user-002", "user-003"]
}
```
Response `201 Created`:
```json
{
  "conversation_id": "conv-abc123",
  "type": "GROUP",
  "name": "Project Alpha",
  "created_at": "2026-05-17T10:00:00Z",
  "members": [
    { "user_id": "user-001", "role": "OWNER" },
    { "user_id": "user-002", "role": "MEMBER" }
  ]
}
```
**Idempotency**: Include `Idempotency-Key: <uuid>` header — duplicate requests within 24h return the original conversation.

---

### Get User's Conversations (Inbox)
```
GET /api/v1/conversations?cursor=<cursor>&limit=20
```
Response `200 OK`:
```json
{
  "conversations": [
    {
      "conversation_id": "conv-abc123",
      "type": "DIRECT",
      "last_message": {
        "message_id": "msg-001",
        "sender_id": "user-002",
        "preview": "Hey, are you there?",
        "sent_at": "2026-05-17T10:30:00Z"
      },
      "unread_count": 3,
      "is_muted": false
    }
  ],
  "next_cursor": "eyJsYXN0X2F0IjoiMjAy...",
  "has_more": true
}
```
**Pagination**: Cursor-based (opaque base64 cursor encodes last_message_at + conversation_id). Never offset-based — at scale, `OFFSET 10000` causes full table scans.

---

### Add Member to Group
```
POST /api/v1/conversations/{conversation_id}/members
```
Request:
```json
{ "user_id": "user-004" }
```
Response `200 OK`:
```json
{ "user_id": "user-004", "role": "MEMBER", "joined_at": "2026-05-17T11:00:00Z" }
```
**Access control**: Only OWNER or ADMIN role may call this endpoint.

---

### Get Conversation Members
```
GET /api/v1/conversations/{conversation_id}/members?cursor=<cursor>&limit=50
```

---

## REST API: Messages

### Get Message History
```
GET /api/v1/conversations/{conversation_id}/messages?before_seq=100&limit=50
```
**Why `before_seq` not `before_timestamp`?** Sequence numbers are stable and unique. Timestamps can collide and have clock skew. Clients use the received `sequence_num` of their oldest loaded message as the cursor.

Response `200 OK`:
```json
{
  "messages": [
    {
      "message_id": "msg-001",
      "sequence_num": 99,
      "sender_id": "user-001",
      "content_type": "TEXT",
      "content": "Hello!",
      "sent_at": "2026-05-17T10:00:00Z",
      "server_received_at": "2026-05-17T10:00:00.123Z",
      "status": "READ",
      "reply_to": null,
      "reactions": [{ "emoji": "👍", "count": 3 }],
      "is_edited": false,
      "is_deleted": false
    }
  ],
  "has_more": true,
  "oldest_seq": 50
}
```

---

### Send Message (via REST — for reconnection fallback)
```
POST /api/v1/conversations/{conversation_id}/messages
```
Request:
```json
{
  "idempotency_key": "client-generated-uuid",
  "content_type": "TEXT",
  "content": "Hello!",
  "reply_to_message_id": null
}
```
Response `202 Accepted`:
```json
{
  "message_id": "msg-001",
  "sequence_num": 100,
  "status": "SENT",
  "server_received_at": "2026-05-17T10:00:00.123Z"
}
```
**Note**: Message send is primarily done over WebSocket for real-time delivery. REST endpoint exists as a fallback for clients with unreliable WebSocket connections.

**Idempotency**: The `idempotency_key` prevents duplicate sends if the client retries after a network timeout. Server stores `idempotency_key → message_id` in Redis with 24h TTL.

---

### Get Pre-Signed Media Upload URL
```
POST /api/v1/media/upload-url
```
Request:
```json
{
  "file_name": "photo.jpg",
  "content_type": "image/jpeg",
  "file_size_bytes": 2048000,
  "conversation_id": "conv-abc123"
}
```
Response `200 OK`:
```json
{
  "upload_url": "https://s3.amazonaws.com/chat-media/...",
  "media_id": "media-001",
  "cdn_url": "https://cdn.chat.example.com/media/media-001/photo.jpg",
  "expires_in_seconds": 300
}
```
Client uploads directly to S3 using the pre-signed URL, then sends a message with `content_type: IMAGE` and `media_url: cdn_url`.

---

### Search Messages
```
GET /api/v1/search/messages?q=hello&conv_id=conv-abc123&limit=20&cursor=<cursor>
```
Response `200 OK`:
```json
{
  "results": [
    {
      "message_id": "msg-001",
      "conversation_id": "conv-abc123",
      "sender_id": "user-001",
      "content": "Hello world",
      "highlight": "<em>Hello</em> world",
      "sent_at": "2026-05-17T10:00:00Z"
    }
  ],
  "total_hits": 42,
  "next_cursor": "eyJ..."
}
```

---

## REST API: Presence

### Get Presence of Users
```
POST /api/v1/presence/query
```
Request:
```json
{ "user_ids": ["user-001", "user-002", "user-003"] }
```
Response `200 OK`:
```json
{
  "presence": {
    "user-001": { "status": "ONLINE", "last_seen": null },
    "user-002": { "status": "OFFLINE", "last_seen": "2026-05-17T09:00:00Z" },
    "user-003": { "status": "AWAY", "last_seen": null }
  }
}
```
**Design note**: POST instead of GET because user_id list can be large (up to 1,000 for a group). GET with query params hits URL length limits.

---

## WebSocket API

### Connection Establishment
```
WebSocket Upgrade Request:
GET wss://chat.example.com/ws
Headers:
  Authorization: Bearer <JWT>
  Sec-WebSocket-Protocol: chat-v1
```

Once connected, client receives a `CONNECTED` frame with server info:
```json
{
  "type": "CONNECTED",
  "server_id": "ws-server-42",
  "user_id": "user-001",
  "reconnect_token": "opaque-token-for-reconnect"
}
```

### WebSocket Frame Format
All WebSocket messages use JSON frames with an envelope:
```json
{
  "frame_id": "client-generated-uuid",
  "type": "<event_type>",
  "payload": { ... }
}
```

Client uses `frame_id` to correlate server ACKs to sent messages.

---

### Client → Server Frames

#### Send Message
```json
{
  "frame_id": "f-001",
  "type": "SEND_MESSAGE",
  "payload": {
    "conversation_id": "conv-abc123",
    "idempotency_key": "ik-uuid-001",
    "content_type": "TEXT",
    "content": "Hello!",
    "reply_to_message_id": null
  }
}
```

Server ACK:
```json
{
  "frame_id": "f-001",
  "type": "MESSAGE_ACK",
  "payload": {
    "message_id": "msg-001",
    "sequence_num": 100,
    "status": "SENT",
    "server_received_at": "2026-05-17T10:00:00.123Z"
  }
}
```

#### Typing Indicator
```json
{
  "frame_id": "f-002",
  "type": "TYPING",
  "payload": {
    "conversation_id": "conv-abc123",
    "is_typing": true
  }
}
```
No server ACK required — fire-and-forget.

#### Mark Messages Read
```json
{
  "frame_id": "f-003",
  "type": "MARK_READ",
  "payload": {
    "conversation_id": "conv-abc123",
    "up_to_sequence_num": 100
  }
}
```

#### Heartbeat (Ping)
```json
{ "type": "PING", "frame_id": "f-004" }
```
Server responds:
```json
{ "type": "PONG", "frame_id": "f-004" }
```

---

### Server → Client Frames

#### New Message Pushed
```json
{
  "type": "NEW_MESSAGE",
  "payload": {
    "message_id": "msg-002",
    "conversation_id": "conv-abc123",
    "sender_id": "user-002",
    "sequence_num": 101,
    "content_type": "TEXT",
    "content": "Hey back!",
    "sent_at": "2026-05-17T10:00:05Z"
  }
}
```

#### Delivery Status Update
```json
{
  "type": "DELIVERY_UPDATE",
  "payload": {
    "message_id": "msg-001",
    "recipient_id": "user-002",
    "status": "DELIVERED",
    "timestamp": "2026-05-17T10:00:01Z"
  }
}
```

#### Typing Indicator from Others
```json
{
  "type": "TYPING_INDICATOR",
  "payload": {
    "conversation_id": "conv-abc123",
    "user_id": "user-002",
    "is_typing": true
  }
}
```

#### Presence Update
```json
{
  "type": "PRESENCE_UPDATE",
  "payload": {
    "user_id": "user-002",
    "status": "ONLINE"
  }
}
```

#### Reconnect Sync (missed messages on reconnect)
```json
{
  "type": "SYNC_REQUIRED",
  "payload": {
    "conversations": [
      {
        "conversation_id": "conv-abc123",
        "missed_count": 5,
        "latest_seq": 105
      }
    ]
  }
}
```
Client then calls REST `GET /messages?before_seq=...` to fetch missed messages.

---

## Error Handling Standards

### HTTP Status Codes

| Status | Meaning |
|--------|---------|
| `400` | Bad request (validation error — field details in `errors[]`) |
| `401` | Unauthenticated (JWT missing or expired) |
| `403` | Unauthorized (user not a member of this conversation) |
| `404` | Resource not found (conversation, message) |
| `409` | Conflict (idempotency key collision — return original response) |
| `413` | Media upload too large (max 100 MB) |
| `429` | Rate limit exceeded |
| `503` | Service temporarily unavailable |

### Error Response Format
```json
{
  "error": {
    "code": "NOT_A_MEMBER",
    "message": "You are not a member of this conversation",
    "request_id": "req-abc123",
    "timestamp": "2026-05-17T10:00:00Z"
  }
}
```

### WebSocket Error Frames
```json
{
  "type": "ERROR",
  "frame_id": "f-001",
  "payload": {
    "code": "MESSAGE_TOO_LARGE",
    "message": "Text messages cannot exceed 4096 characters"
  }
}
```

---

## Rate Limiting

| Endpoint | Limit | Window |
|----------|-------|--------|
| Message send (WebSocket or REST) | 60 messages | per minute per user |
| Media upload | 10 uploads | per minute per user |
| Search | 30 queries | per minute per user |
| Conversation create | 10 creations | per hour per user |
| Presence query | 100 requests | per minute per user |

Rate limit response includes:
```
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 45
X-RateLimit-Reset: 1716000060
Retry-After: 30
```

---

## Pagination Strategy

| Endpoint | Strategy | Why |
|----------|---------|-----|
| Message history | Cursor (sequence_num descending) | Stable ordering, no gaps |
| Conversation list | Cursor (last_message_at + conv_id) | Messages constantly update ordering |
| Group members | Cursor (joined_at + user_id) | Stable membership |
| Search results | Cursor (score + message_id) | Elasticsearch cursor |

**Rejected**: Offset-based pagination. `LIMIT 50 OFFSET 10000` requires scanning 10,050 rows in Cassandra — O(n) cost that makes loading old history progressively slower.

---

## Backward Compatibility Rules

1. Never remove a field from an existing API version
2. Never change the type of a field
3. Never change the semantic meaning of a field (even if its name stays the same)
4. New required fields → version bump
5. New optional fields with defaults → backward compatible, no version bump
6. New enum values → clients must handle unknown enum values gracefully (ignore and display fallback UI)
7. WebSocket frame types: new server→client frame types must be silently ignorable by old clients
