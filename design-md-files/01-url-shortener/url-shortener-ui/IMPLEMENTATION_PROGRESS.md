# Implementation Progress

## Completed

- [x] Vite + React 18 + TypeScript project scaffolding
- [x] Tailwind CSS with brand color tokens + custom animations
- [x] TypeScript interfaces mirroring all backend DTOs exactly
- [x] Axios client with error interceptor normalizing to ApiError shape
- [x] `createShortUrl` API function (omits undefined fields)
- [x] Zustand history store with localStorage persistence (50-entry cap)
- [x] `useCreateUrl` React Query mutation with auto history-save on success
- [x] Clipboard utility with modern API + execCommand fallback
- [x] Time formatting utilities (relative + absolute)
- [x] TTL preset constants (1h/24h/7d/30d/never)
- [x] Button, Input, Badge, Toast UI components
- [x] ShortenForm: RHF + Zod, progressive disclosure for advanced options, API error mapping
- [x] UrlResult: success card with copy, expiry badge, metadata
- [x] UrlHistory: list with copy/remove/clear, empty state, expired badge
- [x] MainLayout: sticky nav, history badge counter
- [x] HomePage, HistoryPage, NotFoundPage
- [x] React Router v6 routing
- [x] Vite dev proxy for /api → localhost:8080
- [x] 19 unit tests (4 files) — all passing
- [x] Production build — zero errors, 344 kB JS, 17 kB CSS

## Pending (V2)

- [ ] QR code generation for created short URLs
- [ ] Search/filter in history page
- [ ] Copy stats / click count (requires backend analytics endpoint)
- [ ] Dark mode
- [ ] Pagination for history (currently capped at 50 in localStorage)
- [ ] E2E tests (Playwright) against running backend

## Known Constraints

- History is browser-local (no user accounts in V1 backend)
- Short URL redirects go directly to `localhost:8080/{shortCode}` — not through the SPA
- No analytics display (backend doesn't expose click counts in V1)
