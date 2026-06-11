# Frontend Architecture — Obsidian Chat UI

## Stack

| Concern | Choice | Reason |
|---------|--------|--------|
| Build | Vite | Fast HMR, native ESM, proxy support |
| Framework | React 18 | StrictMode, concurrent features |
| Language | TypeScript strict | Type safety across WS frames and DTOs |
| Styling | Tailwind CSS v3 | Utility-first, custom design tokens in config |
| Routing | React Router v6 | Nested routes, protected routes via Outlet |
| Server state | TanStack Query v5 | Caching, `setQueryData` for WS updates, infinite queries |
| Client state | Zustand v5 | Auth + toast — minimal global state |
| Forms | React Hook Form + Zod | Backend validation mirrored in schema |
| HTTP | Axios | Interceptors for JWT injection and 401 redirect |
| WebSocket | Native browser WS | Reconnect logic with ping/pong keepalive |

## Folder Structure

```
src/
├── api/
│   ├── client.ts          # Axios instance + JWT interceptor + 401 handler
│   ├── auth.ts            # register(), login()
│   ├── conversations.ts   # list(), get(), create(), members(), addMember()
│   ├── messages.ts        # history(), send()
│   └── presence.ts        # query()
├── types/
│   └── index.ts           # All TS interfaces matching backend DTOs exactly
├── store/
│   ├── authStore.ts       # Zustand: user, token — persisted to localStorage
│   └── toastStore.ts      # Zustand: toast queue
├── hooks/
│   └── useWebSocket.ts    # WS connect/reconnect, ping timer, NEW_MESSAGE handler
├── components/
│   ├── ui/
│   │   ├── Button.tsx
│   │   ├── Input.tsx
│   │   ├── Avatar.tsx
│   │   ├── ToastContainer.tsx
│   │   └── ErrorBoundary.tsx
│   ├── layout/
│   │   └── AppLayout.tsx   # 2-panel layout + WS lifecycle
│   ├── conversation/
│   │   ├── ConversationList.tsx
│   │   ├── ConversationItem.tsx
│   │   └── NewConversationModal.tsx
│   └── message/
│       ├── MessageBubble.tsx
│       ├── MessageList.tsx  # useInfiniteQuery + WS merge
│       └── MessageInput.tsx # WS-first, REST fallback
├── pages/
│   ├── LoginPage.tsx
│   ├── RegisterPage.tsx
│   └── ChatPage.tsx
├── routes/
│   ├── index.tsx
│   └── ProtectedRoute.tsx
├── utils/
│   └── formatting.ts       # formatTime, formatDate, formatRelative
└── test/                   # Vitest + RTL
```

## Data Flow

### Authentication
```
LoginPage → authApi.login → authStore.setUser → localStorage
↓
React Router redirects to /
↓
useAuthStore reads token in each request via Axios interceptor
```

### WebSocket Lifecycle
```
AppLayout mounts → useWebSocket() hook
↓
connect() → ws://host/ws?token=JWT
↓
onopen → start PING interval (25s)
↓
onmessage NEW_MESSAGE →
  queryClient.setQueryData(['messages', conversationId], append)
  queryClient.invalidateQueries(['conversations'])
↓
onclose → reconnect after 3s (max 10 attempts)
```

### Message Sending
```
MessageInput → sendFrame(SEND_MESSAGE)
↓ if WS open
WS frame sent → wait for MESSAGE_ACK (no UI blocking)
↓ if WS closed
REST POST /api/v1/conversations/{id}/messages
→ queryClient.setQueryData append result
```

### Message History + Pagination
```
MessageList mounts for conversationId
↓
useInfiniteQuery(['messages-paged', id])
  initialPageParam: undefined (no beforeSeq → latest)
  getNextPageParam: lastPage.oldestSeq
↓
Flat list = pages.flatMap(p => [...p.messages].reverse())
  + WS-pushed messages not yet in paged cache
↓
Scroll to top → fetchNextPage(oldestSeq) → load older messages
```

## State Management

### What lives in Zustand
- `authStore`: `{user: AuthUser | null}` — persisted to `chat:auth` key
- `toastStore`: `{toasts: Toast[]}` — ephemeral, in-memory

### What lives in React Query
- `['conversations']` — flat list, refetch every 30s + on WS NEW_MESSAGE
- `['conversation', id]` — single conversation detail, stale after 60s
- `['messages-paged', id]` — infinite query, staleTime: Infinity (WS updates)
- `['messages', id]` — flat merged list (paged + WS), used for display
- `['presence', memberIds]` — refetch every 15s

### What is ephemeral component state
- `activeId` in AppLayout — selected conversation
- `showModal` in ConversationList — modal open/close
- `value` in MessageInput — textarea content

## Auth Flow

```
Register/Login → AuthResponse { userId, username, displayName, token }
↓
Zustand authStore.setUser(data) → localStorage 'chat:auth'
↓
Axios interceptor reads localStorage on every request
  → sets Authorization: Bearer {token}
↓
401 response → interceptor clears storage, redirect /login
↓
ProtectedRoute checks isAuthenticated() → Outlet or Navigate /login
```

## Design System

**Palette (Tailwind custom `ch.*` tokens):**
- `ch-base` #080910 — page background
- `ch-surface` #0f1018 — panel backgrounds  
- `ch-elevated` #161724 — cards, inputs, bubbles
- `ch-border` #22253a — subtle dividers
- `ch-accent` #f59e0b — CTA, active states (amber gold)
- `ch-mine` #1e2a4a / `ch-mine-border` #2d4a8a — own message bubbles

**Typography:**
- `font-display` (Syne) — headings, labels, logo
- `font-body` (Plus Jakarta Sans) — body text
