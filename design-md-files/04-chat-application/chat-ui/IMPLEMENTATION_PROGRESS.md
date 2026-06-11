# Implementation Progress

## Completed

### Auth
- [x] Register form with Zod validation mirroring backend constraints
- [x] Login form
- [x] JWT stored in Zustand + persisted to `localStorage` via `zustand/middleware/persist`
- [x] Axios interceptor injects Bearer token on every request
- [x] 401 interceptor clears auth + redirects to `/login`
- [x] Protected route via React Router Outlet pattern

### Conversations
- [x] List conversations (polled every 30s)
- [x] Create conversation modal (DIRECT / GROUP / CHANNEL)
- [x] Conversation item with type icon, member count, last message time
- [x] Empty state + loading skeleton

### Messaging
- [x] Message history with cursor-based infinite scroll (scroll up = load older)
- [x] Own vs other message bubble styling
- [x] Date dividers between messages
- [x] Message delivery status icons (SENT / DELIVERED / READ)
- [x] Textarea auto-expand, Enter to send, Shift+Enter for newline
- [x] Idempotency key (UUID) on every send for dedup
- [x] WS-first send with REST fallback when WS disconnected

### WebSocket
- [x] Connect on mount with JWT query param
- [x] Automatic reconnect (up to 10 attempts, 3s delay)
- [x] PING/PONG keepalive every 25s
- [x] `NEW_MESSAGE` → append to React Query cache + invalidate conversation list

### Presence
- [x] Batch presence query for active conversation members
- [x] Polled every 15s
- [x] Online member count in chat header
- [x] Color-coded dots (green/yellow/grey) in header

### UI / UX
- [x] "Obsidian" dark theme with amber gold accent
- [x] Syne + Plus Jakarta Sans typography
- [x] Custom scrollbar styling
- [x] Toast notifications (success / error / info)
- [x] Error boundary on chat page
- [x] Avatar with name-based color generation
- [x] Mobile responsive (slide-in sidebar on mobile)
- [x] Loading skeletons for conversations and messages

### Testing
- [x] Utility function unit tests (`formatting.ts`)
- [x] Button component tests
- [x] authStore unit tests
- [x] Vitest + React Testing Library configured

### Documentation
- [x] `FRONTEND_ANALYSIS.md` — API contracts, integration matrix, limitations
- [x] `FRONTEND_ARCHITECTURE.md` — data flow, state management, design tokens

## Pending / Known Issues

1. **No user search** — Creating a DIRECT conversation requires knowing the other user's UUID. A future `GET /api/v1/users?q=` endpoint would enable a people picker.

2. **Sender display names** — `ConversationMember` DTO does not include `username`/`displayName`. Messages show `userId[:8]` as sender name. Would require a user profile endpoint.

3. **MESSAGE_ACK not wired to UI** — WS ACK updates `message_id`/`sequence_num`/`status` but the frontend doesn't currently merge ACK into the optimistic message. Messages sent via WS appear as DELIVERED (from subsequent REST load) not SENT initially.

4. **No read receipt UI** — Backend tracks `MessageStatus.READ` but has no endpoint to mark messages as read. The three status ticks exist in the UI but won't advance to READ without a backend endpoint.

5. **Presence not auth-required** — Backend security config does not require auth for presence queries. Any client can query any user's presence. Noted for security review.

6. **Max group size** — Backend enforces `chat.mvp.max-group-members=20`. Frontend has no validation for this yet; backend will return a 400.

7. **Search not functional** — The search input in ConversationList is UI-only; no backend search endpoint exists.

## Setup

```bash
cd chat-ui
cp .env.example .env
npm install
npm run dev
```

Backend must run on port 8080. Vite proxies `/api` and `/ws` to localhost:8080.
