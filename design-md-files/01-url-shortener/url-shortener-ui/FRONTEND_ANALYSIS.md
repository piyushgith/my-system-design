# Frontend Analysis

## Business Understanding

Single-purpose URL shortener MVP. No user accounts in V1. Backend handles redirect
(302) directly — the frontend is a creation tool, not a redirect handler.

## API Analysis

### POST /api/v1/urls → 201 Created
| Field | Type | Constraint |
|-------|------|------------|
| longUrl | string | required, http/https, max 2048 |
| alias | string? | 3–10 chars, `[a-zA-Z0-9_-]` |
| ttl | Long? | positive, seconds |

Response: `{ shortUrl, shortCode, longUrl, createdAt, expiresAt }`

### GET /{shortCode} → 302/404/410
Backend-handled redirect. Frontend never calls this.

### Error body shape
```json
{ "error": { "code": "...", "message": "...", "field": "...", "timestamp": "..." } }
```

Error codes: `INVALID_URL`, `INVALID_ALIAS`, `RESERVED_ALIAS`, `ALIAS_CONFLICT`,
`VALIDATION_ERROR`, `SHORT_CODE_GENERATION_FAILED`

## Screen Inventory

| Screen | Path | Purpose |
|--------|------|---------|
| Home | `/` | Shorten form + result card |
| History | `/history` | Local browser history |
| Not Found | `/*` | 404 fallback with explanation |

## Architecture Decisions

**No auth layer** — backend V1 has no Spring Security.

**Local history via localStorage** — no user/session API in backend. Zustand persist
middleware handles serialization.

**Vite dev proxy** — `/api` → `localhost:8080` avoids CORS in dev without backend changes.

**React Query mutations only** — no queries (no GET endpoints to call from frontend).

**Field-level error mapping** — ApiError.field/code mapped to specific form fields;
unknown errors surface as root-level form error.
