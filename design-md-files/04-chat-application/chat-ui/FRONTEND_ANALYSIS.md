# Frontend Analysis — Obsidian Chat UI

## Business Understanding

A real-time chat application with WebSocket-first delivery. Supports direct messages, group chats, and channels. Users can see presence (online/away/offline) and send messages with delivery status tracking (SENT → DELIVERED → READ).

## API Analysis

### AuthController — `/api/v1/auth`

| Endpoint | Method | Auth | Request | Response |
|----------|--------|------|---------|---------|
| `/register` | POST | None | `{username, displayName, email, password}` | `{userId, username, displayName, token}` |
| `/login` | POST | None | `{login, password}` | `{userId, username, displayName, token}` |

**Validation:**
- `username`: 3–50 chars, non-blank
- `displayName`: max 100 chars, non-blank
- `email`: valid email format
- `password`: 8–72 chars

### ConversationController — `/api/v1/conversations`

| Endpoint | Method | Auth | Request | Response |
|----------|--------|------|---------|---------|
| `/` | POST | JWT | `{type, name?, memberIds[]}` | `ConversationResponse` |
| `/` | GET | JWT | `?limit=20` | `{conversations[], hasMore}` |
| `/{id}` | GET | JWT | — | `ConversationResponse` |
| `/{id}/members` | GET | JWT | — | `MemberResponse[]` |
| `/{id}/members` | POST | JWT | `{userId}` | `MemberResponse` |

**Types:** `DIRECT`, `GROUP`, `CHANNEL`
**Roles:** `OWNER`, `ADMIN`, `MEMBER`

### MessageController — `/api/v1/conversations/{id}/messages`

| Endpoint | Method | Auth | Request | Response |
|----------|--------|------|---------|---------|
| `/` | GET | JWT | `?beforeSeq=&limit=50` | `{messages[], hasMore, oldestSeq}` |
| `/` | POST | JWT | `{idempotencyKey?, contentType, content}` | `MessageResponse` |

**Pagination:** cursor-based via `beforeSeq` (not offset). Backend returns DESC, client reverses.
**Idempotency:** UUID per message, enables safe WS retries with REST fallback.

### PresenceController — `/api/v1/presence/query`

| Endpoint | Method | Auth | Request | Response |
|----------|--------|------|---------|---------|
| `/query` | POST | None | `{userIds[]}` | `{presence: {userId: {status, lastSeen}}}` |

**Status values:** `ONLINE`, `AWAY`, `OFFLINE`

### WebSocket — `ws://host/ws?token=JWT`

**Connection:** JWT via query param (not header, WebSocket limitation).

**Client → Server frames:**
```json
{ "type": "SEND_MESSAGE", "frame_id": "uuid", "payload": {
    "conversation_id": "uuid", "content_type": "TEXT",
    "content": "...", "idempotency_key": "uuid" }}
{ "type": "PING", "frame_id": "uuid" }
```

**Server → Client frames:**
```json
{ "type": "CONNECTED", "payload": { "server_id": "...", "user_id": "..." }}
{ "type": "MESSAGE_ACK", "frame_id": "...", "payload": { "message_id", "sequence_num", "status", "server_received_at" }}
{ "type": "NEW_MESSAGE", "payload": { "message_id", "conversation_id", "sender_id", "sequence_num", "content_type", "content", "sent_at" }}
{ "type": "PONG", "frame_id": "..." }
{ "type": "ERROR", "frame_id": "...", "payload": { "code", "message" }}
```

## Frontend Integration Matrix

| Feature | API | Method | Request DTO | Response DTO |
|---------|-----|--------|-------------|-------------|
| Register | `/api/v1/auth/register` | POST | `RegisterRequest` | `AuthResponse` |
| Login | `/api/v1/auth/login` | POST | `LoginRequest` | `AuthResponse` |
| List conversations | `/api/v1/conversations` | GET | `limit` param | `ConversationListResponse` |
| Create conversation | `/api/v1/conversations` | POST | `CreateConversationRequest` | `ConversationResponse` |
| Get conversation | `/api/v1/conversations/{id}` | GET | — | `ConversationResponse` |
| Get members | `/api/v1/conversations/{id}/members` | GET | — | `MemberResponse[]` |
| Add member | `/api/v1/conversations/{id}/members` | POST | `AddMemberRequest` | `MemberResponse` |
| Message history | `/api/v1/conversations/{id}/messages` | GET | `beforeSeq`, `limit` | `MessageHistoryResponse` |
| Send message (REST) | `/api/v1/conversations/{id}/messages` | POST | `SendMessageRequest` | `MessageResponse` |
| Send message (WS) | `/ws?token=JWT` | WS | `SEND_MESSAGE` frame | `MESSAGE_ACK` frame |
| Receive messages (WS) | `/ws?token=JWT` | WS | — | `NEW_MESSAGE` frame |
| Query presence | `/api/v1/presence/query` | POST | `{userIds[]}` | `PresenceQueryResponse` |

## Screen Inventory

1. **Login page** — `/login`
2. **Register page** — `/register`
3. **Chat page** — `/` (protected)
   - Conversation list panel
   - Main chat area (message list + input)
   - New conversation modal

## Limitations and Open Questions

1. **No user search API** — Creating conversations requires knowing the target user's UUID. In production, a `GET /api/v1/users?q=` endpoint would be needed.
2. **No display names for other users** — `ConversationMember` only carries `{userId, role, joinedAt}`. Frontend shows `userId[:8]` as sender name.
3. **No typing indicators** — Backend has no WS frame for this.
4. **No read receipts management** — `MessageStatus` includes `READ` but no endpoint to mark messages read.
5. **Presence not auth-gated** — The presence endpoint requires no JWT (SecurityConfig confirms this). Querying any userId's presence is possible.
