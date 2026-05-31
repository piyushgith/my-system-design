# Frontend Architecture

## Folder Structure

```
src/
├── api/
│   ├── client.ts          Axios instance + error interceptor
│   └── urls.ts            createShortUrl() function
├── types/
│   └── api.ts             TypeScript interfaces matching backend DTOs exactly
├── store/
│   └── historyStore.ts    Zustand + localStorage persistence (50-entry cap)
├── hooks/
│   └── useCreateUrl.ts    React Query mutation wrapping createShortUrl
├── utils/
│   ├── clipboard.ts       Copy to clipboard (modern + execCommand fallback)
│   └── time.ts            formatRelativeTime, formatDateTime
├── constants/
│   └── ttlOptions.ts      TTL preset options (1h/24h/7d/30d/never)
├── components/ui/
│   ├── Button.tsx         Variants: primary, secondary, ghost, danger
│   ├── Input.tsx          Label + error + hint
│   ├── Badge.tsx          5 color variants
│   └── Toast.tsx          Auto-dismiss toast with manual close
├── features/url-shortener/
│   ├── ShortenForm.tsx    RHF + Zod form, advanced options toggle
│   ├── UrlResult.tsx      Success card with copy button + metadata
│   └── UrlHistory.tsx     History list with copy/remove/clear
├── pages/
│   ├── HomePage.tsx       Form + result + how-it-works cards
│   ├── HistoryPage.tsx    Wraps UrlHistory
│   └── NotFoundPage.tsx   404 with note about backend redirect
├── layouts/
│   └── MainLayout.tsx     Sticky header with nav + history badge count
└── test/
    ├── setup.ts           jest-dom matchers
    ├── historyStore.test.ts
    ├── urlsApi.test.ts
    ├── Badge.test.tsx
    └── time.test.ts
```

## Data Flow

```
ShortenForm → useCreateUrl (mutation) → createShortUrl (Axios POST)
                    ↓ onSuccess
              historyStore.add()         → localStorage
                    ↓
              HomePage.setResult()       → UrlResult card
```

## State Management

| State | Where |
|-------|-------|
| Server mutation state (loading/error) | React Query useMutation |
| Last created URL result | Local useState in HomePage |
| URL history (persisted) | Zustand + zustand/middleware/persist |
| Form state | React Hook Form |
| Toast visible | Local useState in each component |

No global UI state store — Zustand used only for cross-session persistence.

## Authentication Flow

None in V1. Backend has no security configuration.

## Error Handling Strategy

1. Axios interceptor catches non-2xx and normalizes to `ApiError` shape
2. `useCreateUrl.onError` receives `ApiError`
3. `ShortenForm.mapApiError()` routes to field error or root error based on `field`/`code`
4. React Hook Form renders field errors inline; root error renders below form
