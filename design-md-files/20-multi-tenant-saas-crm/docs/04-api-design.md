# 04 — API Design

## Objective

Define the REST API surface for the Multi-Tenant SaaS CRM: URL structure, versioning, authentication, pagination, error handling, idempotency, and evolution strategy. This drives the contract between frontend, mobile, and third-party integrations.

---

## API Design Principles

1. **Tenant isolation is mandatory at the API layer**: Every request must carry a validated tenant identity. No request can be processed without an authenticated tenant context.
2. **Backward compatibility is a first-class constraint**: Once a v1 API is in production, breaking changes require a new version. Old versions must be supported for at least 12 months with deprecation warnings.
3. **Pagination on all list endpoints**: No endpoint returns unbounded lists. Cursor-based pagination for consistency under concurrent writes.
4. **Idempotency on all mutation endpoints**: Clients must be able to safely retry failed requests.
5. **Structured error responses**: Every error returns a machine-parseable body with an error code, human-readable message, and correlation ID.

---

## Authentication & Tenant Context

### JWT Authentication Flow

All API requests carry a JWT in the `Authorization: Bearer <token>` header. The JWT contains:
- `sub`: user_id
- `tid`: tenant_id
- `roles`: list of role codes
- `scope`: permissions granted
- `exp`: expiry (short-lived: 15 minutes)

A separate refresh token (opaque, stored in Redis) is used to obtain new JWTs without re-login.

### API Key Authentication (Machine-to-Machine)

Tenants can generate API keys for programmatic access. API keys are:
- Prefixed with `crm_` for easy identification in logs
- Stored as `HMAC-SHA256(key)` in the database (never plaintext)
- Scoped to specific operations (read-only, write, admin)
- Rate-limited per key (tier-based limits)
- Associated with a service account user within the tenant

### Tenant Context Resolution

The API Gateway extracts `tid` from the JWT and sets `X-Tenant-ID` header before forwarding to the application. The application trusts this header only from the internal network (API Gateway). Direct external requests cannot spoof this header.

---

## URL Structure

```
Base URL: https://api.crm.io/v1

Tenant-scoped resources:
  /contacts
  /contacts/{contact_id}
  /contacts/{contact_id}/activities
  /accounts
  /accounts/{account_id}
  /deals
  /deals/{deal_id}
  /pipelines
  /pipelines/{pipeline_id}/stages
  /activities
  /users
  /custom-fields
  /workflows
  /audit-logs

Tenant configuration:
  /tenant/settings
  /tenant/feature-flags
  /tenant/users
  /tenant/roles

Search:
  /search?q=...&entity_type=contact,deal&...

Admin (internal):
  /internal/admin/tenants
  /internal/admin/tenants/{tenant_id}/suspend
```

Note: The tenant ID is NOT in the URL path. It is derived from the authenticated user's JWT. This prevents clients from accidentally querying another tenant's resources even if they guess the tenant ID.

---

## Key API Endpoints

### Contacts

```
GET    /v1/contacts              — List contacts (paginated, filtered)
POST   /v1/contacts              — Create contact
GET    /v1/contacts/{id}         — Get contact by ID
PUT    /v1/contacts/{id}         — Full update
PATCH  /v1/contacts/{id}         — Partial update
DELETE /v1/contacts/{id}         — Soft delete

GET    /v1/contacts/{id}/activities   — List activities for contact
POST   /v1/contacts/{id}/activities   — Log activity on contact
GET    /v1/contacts/{id}/deals        — List deals associated with contact
GET    /v1/contacts/{id}/audit-trail  — Audit history for this contact
POST   /v1/contacts/bulk              — Bulk create/update (async, returns job_id)
GET    /v1/contacts/export            — Trigger GDPR export (async)
```

### Deals

```
GET    /v1/deals
POST   /v1/deals
GET    /v1/deals/{id}
PATCH  /v1/deals/{id}
DELETE /v1/deals/{id}
POST   /v1/deals/{id}/stage-transition — Move deal to a new stage
GET    /v1/deals/{id}/history          — Stage transition history
```

### Search

```
GET /v1/search?q=john+doe&entity_type=contact,account&filter[owner_id]=user_abc&sort=created_at:desc&page_size=20&cursor=xxx
```

---

## Versioning Strategy

**URI versioning** (`/v1/`, `/v2/`) is chosen over header versioning for simplicity and cacheability. Header versioning (`Accept: application/vnd.crm.v2+json`) is cleaner but:
- Breaks CDN caching (Vary header bloat)
- Harder to test in browsers
- Confusing for third-party developers

**Version lifecycle**:
1. `/v2` ships alongside `/v1` — both active
2. `/v1` enters **deprecation** period — returns `Deprecation: true` header with sunset date
3. After sunset date (12 months minimum), `/v1` returns `410 Gone` with migration instructions
4. API changelog maintained at `/v1/changelog` and developer portal

**Non-breaking changes** do NOT require a new version:
- Adding new optional fields to response bodies
- Adding new optional query parameters
- Adding new endpoints
- Adding new error codes

**Breaking changes** require a new version:
- Removing or renaming fields
- Changing field types
- Changing HTTP methods
- Changing pagination model

---

## Pagination Strategy

**Cursor-based pagination** for all list endpoints:

```
Request:  GET /v1/contacts?cursor=eyJpZCI6IjEyMyJ9&page_size=50
Response:
{
  "data": [...],
  "meta": {
    "total_count": 5420,
    "page_size": 50,
    "next_cursor": "eyJpZCI6IjE3MyJ9",
    "prev_cursor": "eyJpZCI6IjczIn0=",
    "has_next": true,
    "has_prev": true
  }
}
```

Cursor encodes: `{"id": "last_seen_id", "sort_field_value": "..."}` — opaque to clients (Base64 encoded JSON).

**Why cursor over offset-based pagination**:
- Offset pagination has the "skipping rows" problem: if rows are inserted/deleted between pages, items are missed or duplicated
- At scale, `OFFSET 50000` requires the database to scan and discard 50,000 rows
- Cursor pagination is O(1) — uses the index for the last seen ID

**Offset pagination** is offered as a secondary option for export/analytics use cases where exact page counts matter and consistency under writes is not required.

---

## Idempotency Strategy

All `POST` and `PATCH` endpoints accept an `Idempotency-Key` header:

```
POST /v1/contacts
Idempotency-Key: client-generated-uuid-v4
```

The server stores `(tenant_id, idempotency_key)` → `(status_code, response_body)` in Redis with a 24-hour TTL.

On duplicate request: return the stored response immediately without re-processing.

**Implementation**:
- Redis key: `idempotency:{tenant_id}:{key}` — tenant-namespaced to prevent cross-tenant key collisions
- If a request is in-flight (status=PROCESSING): return `409 Conflict` with retry guidance
- After 24 hours: key expires, client must generate a new key for retries

**Why this matters for CRM**: A client creating a contact may time out and retry — without idempotency, the contact is created twice. With it, the retry returns the original response.

---

## Error Response Standard

All errors follow a consistent structure:

```json
{
  "error": {
    "code": "CONTACT_NOT_FOUND",
    "message": "Contact with ID 'abc-123' was not found in your organization",
    "details": [],
    "correlation_id": "req-7f8a-9b23-...",
    "documentation_url": "https://docs.crm.io/errors/CONTACT_NOT_FOUND"
  }
}
```

**Error Code Categories**:

| HTTP Status | Error Code Pattern | Meaning |
|---|---|---|
| 400 | `VALIDATION_*` | Invalid request body |
| 401 | `AUTH_*` | Not authenticated |
| 403 | `PERMISSION_*` | Not authorized |
| 404 | `*_NOT_FOUND` | Resource not found |
| 409 | `*_CONFLICT` | Duplicate or state conflict |
| 422 | `BUSINESS_RULE_*` | Business logic violation |
| 429 | `RATE_LIMIT_EXCEEDED` | Too many requests |
| 500 | `INTERNAL_ERROR` | System fault (never leak details) |
| 503 | `SERVICE_UNAVAILABLE` | Maintenance or overload |

---

## Rate Limiting

Rate limits are enforced at the API Gateway, per tenant, per endpoint category:

| Tier | Standard API | Bulk Operations | Search |
|---|---|---|---|
| Starter | 100 req/min | 2 concurrent jobs | 20 req/min |
| Growth | 500 req/min | 5 concurrent jobs | 100 req/min |
| Enterprise | 2000 req/min | 20 concurrent jobs | 500 req/min |

Rate limit headers on every response:
```
X-RateLimit-Limit: 500
X-RateLimit-Remaining: 347
X-RateLimit-Reset: 1716000060
Retry-After: 23  (only when 429)
```

**Algorithm**: Token bucket per tenant, refilled per minute. Stored in Redis with atomic Lua scripts to prevent race conditions.

---

## Bulk Operations

Long-running operations (bulk import, bulk update, GDPR export) return a `job_id` immediately:

```
POST /v1/contacts/bulk
→ 202 Accepted
{
  "job_id": "job-abc-123",
  "status": "QUEUED",
  "estimated_duration_seconds": 30,
  "status_url": "/v1/jobs/job-abc-123"
}

GET /v1/jobs/job-abc-123
→ {
  "job_id": "job-abc-123",
  "status": "RUNNING",
  "progress": { "processed": 450, "total": 1000, "errors": 2 }
}
```

Job state is stored in Redis (short-lived) and PostgreSQL (permanent record for audit).

---

## GraphQL API (V2 Roadmap)

GraphQL is deferred to V2 for enterprise integrations:
- Reduces over-fetching for complex CRM views (Contact + Deals + Activities in one query)
- Enables batch loading via DataLoader to avoid N+1 queries
- Schema introspection aids third-party developer tooling

**Risk**: GraphQL query complexity attacks — require query depth limiting and query cost analysis before exposing to third parties.

---

## API Evolution Strategy

| Strategy | Approach |
|---|---|
| Adding fields | Always backward compatible — add with null/default |
| Removing fields | Deprecate in current version, remove in next version |
| Renaming fields | Add new name, deprecate old, remove in next version |
| Behavioral changes | New version required |
| Webhook payload changes | Versioned webhook event schemas |

**Consumer-Driven Contract Testing**: When extracting microservices, use Pact to define API contracts between services. Prevents accidental breaking changes.

---

## Interview Discussion Points

- **Why not GraphQL from the start?** → REST is simpler to cache, rate-limit, and version. Most CRM operations are simple CRUD. GraphQL complexity is justified when clients need flexible queries — V2 when enterprise customer demand makes it worthwhile.
- **How do you handle API versioning across 10,000 tenants with different integration versions?** → Track API version usage per tenant in analytics. Before deprecating v1, identify which tenants still call v1 endpoints and notify them directly. Version sunset is gated on zero active usage (or contractual override for Enterprise).
- **What prevents a tenant from calling `/v1/contacts` and getting another tenant's data if they know a contact_id?** → The application always adds `AND tenant_id = {current_tenant_id}` to every query. PostgreSQL RLS enforces this at the database level independently. The URL path contains only the resource ID, never a tenant identifier.
- **How do you test that rate limiting works correctly?** → Load test each tier configuration in staging. Test that exceeding the limit returns 429 with correct `Retry-After`. Test that the rate limit counter resets correctly after the window. Test that burst traffic within token bucket capacity is accepted.
