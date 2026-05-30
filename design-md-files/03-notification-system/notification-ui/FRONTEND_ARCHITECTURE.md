# Frontend Architecture — Notification Hub UI

## Stack

| Concern | Library | Why |
|---|---|---|
| Build | Vite 5 | Fast HMR, native ESM, first-class TypeScript |
| UI | React 18 + TypeScript strict | Concurrent features, hooks |
| Styling | Tailwind CSS 3 | Utility-first, no runtime CSS, Purge for prod |
| Routing | React Router v6 | Nested routes, loader/action pattern |
| Server state | TanStack Query v5 | Caching, background refetch, polling, mutations |
| Client state | Zustand + persist | Minimal auth store, persisted to localStorage |
| Forms | React Hook Form v7 + Zod | Uncontrolled inputs, schema-driven validation |
| HTTP | Axios | Interceptors for auth injection and 401 redirect |

## Directory Structure

```
src/
  api/           # Axios functions, one file per resource
  components/
    ui/          # Primitives: Badge, Button, Input, Select, Card, Spinner, Toast, ErrorBoundary
    layout/      # Navbar, Sidebar, Layout (role-filtered nav)
    inbox/       # InboxItemCard
    notifications/ # StatusBadge, DeliveryAttemptRow
    preferences/ # PreferenceToggle
  constants/     # Label maps, color maps, enum arrays
  hooks/         # useNotifications, useInbox, usePreferences, useTemplates
  pages/         # One file per route
  routes/        # ProtectedRoute, router config
  store/         # Zustand auth store
  types/         # TypeScript interfaces mirroring backend DTOs exactly
  utils/         # format.ts (date-fns), errors.ts (AxiosError parsing)
  test/          # Vitest + RTL tests mirroring src structure
```

## Auth Design

Backend uses platform JWTs (service-to-service). No login endpoint exists. Frontend implements mock auth:
- User enters UUID + display name + role
- Token stored as `demo-jwt-{userId}` (not a real JWT)
- Axios request interceptor injects `Authorization: Bearer <token>`
- Axios response interceptor redirects to `/login` on 401
- Zustand `persist` middleware saves to `notif-auth` in localStorage

**Production path**: swap `LoginPage` + `useAuthStore` for real OIDC/OAuth2 flow. All interceptors and protected routes remain unchanged.

## Role-Based Access

Three roles: `admin`, `producer`, `user`.

| Route | admin | producer | user |
|---|---|---|---|
| `/` Dashboard | ✓ | ✓ | ✓ |
| `/send` | ✓ | ✓ | — |
| `/notifications/:id` | ✓ | ✓ | ✓ |
| `/inbox` | ✓ | — | ✓ |
| `/preferences` | ✓ | — | ✓ |
| `/templates` | ✓ | — | — |
| `/templates/create` | ✓ | — | — |
| `/unsubscribe` | public | public | public |

Enforcement: React Router nested `ProtectedRoute` elements with `allowedRoles` prop. Unauthorized users redirect to `/`.

## Server State Strategy

- **TanStack Query** owns all remote data. No manual `useEffect` for fetching.
- Notification detail auto-polls every 3 seconds until terminal status (DELIVERED / PARTIALLY_DELIVERED / FAILED / CANCELLED / EXPIRED).
- Preferences staleTime: 60s (rarely changes).
- Templates staleTime: 300s (very stable).
- Inbox staleTime: 10s (frequently updated).
- Mutations invalidate related queries on success.

## Form Strategy

All forms use React Hook Form + Zod resolver. Schema definitions mirror backend validation:
- UUID fields validated with `z.string().uuid()`
- Enum fields use `z.enum([...])` matching backend enum values exactly
- Optional fields use `.optional()` — empty string coerced to `undefined` before submit
- `useFieldArray` for dynamic key-value variable pairs

## Error Handling

Three layers:
1. `ErrorBoundary` at root — catches uncaught render errors, shows try-again UI
2. Per-query `isError` state — inline error states in each page
3. Toast notifications — mutation errors surfaced via `useToast()`

`extractErrorMessage` parses Axios errors: first checks `response.data.error.message` (backend `ErrorResponse` shape), falls back to `err.message`, then generic fallback.

## Key Design Decisions

### Why not Redux?
Single auth slice is all client state needed. Zustand is 1KB vs Redux toolkit's 40KB+ for this use case.

### Why TanStack Query over SWR?
v5 API is cleaner, devtools are better, `refetchInterval` function form (for conditional polling) is unique to TQ.

### Why Tailwind not a component library?
System design practice — building from scratch reinforces layout fundamentals. No MUI/shadcn dependency lock-in.

### Why React Router v6 nested routes over manual guards?
Declarative, co-located with route config, easier to reason about. Auth check happens before layout mount — no flash of protected content.
