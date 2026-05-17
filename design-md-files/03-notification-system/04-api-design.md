# 04 — API Design: Notification System

---

## Objective

Define the REST API contracts for the Notification System. Cover versioning, pagination, idempotency, error handling, and the evolution strategy for producer-facing and user-facing APIs.

---

## API Surface Overview

| API Group | Audience | Purpose |
|-----------|---------|---------|
| Notification Submission API | Internal producers (services) | Submit notifications for delivery |
| Notification Status API | Internal producers | Query delivery status |
| User Preference API | End users (via frontend) | Manage notification preferences |
| In-App Inbox API | End users (via frontend) | Read and manage in-app notifications |
| Template Management API | Operators / Admin | CRUD for notification templates |
| Campaign Management API | Growth / Marketing team | Create and launch batch campaigns |
| Unsubscribe API | End users (via email link) | One-click unsubscribe |

---

## API Versioning Strategy

- URI-based versioning: `/api/v1/`, `/api/v2/`
- Major version increments only for breaking changes (field removal, semantic change)
- Non-breaking additions (new optional fields, new endpoints) are additive within the same version
- Old versions supported for 12 months after a new major version is released
- Version sunset communicated via `Deprecation` and `Sunset` response headers

---

## Base URL

```
Internal (service-to-service): https://notification-api.internal/api/v1
External (user-facing):        https://api.yourplatform.com/notifications/v1
```

---

## Notification Submission API

### Submit Single Notification

```
POST /api/v1/notifications
Authorization: Bearer <service-jwt>
Idempotency-Key: <producer-generated UUID>
Content-Type: application/json

Request Body:
{
  "category": "TRANSACTIONAL",
  "priority": "HIGH",
  "recipient_user_id": "usr-123e4567",
  "template_id": "order-confirmed",
  "template_version": 3,              // optional; defaults to latest active
  "variables": {
    "first_name": "Alice",
    "order_id": "ORD-789",
    "order_total": "₹1,499"
  },
  "scheduled_at": null,               // null = immediate
  "expires_at": "2026-05-18T10:00:00Z",
  "channels_override": ["EMAIL"],     // optional; overrides user preferences
  "producer_context": {
    "service": "order-service",
    "trace_id": "abc123"
  }
}

Response: 202 Accepted
{
  "notification_id": "ntf-550e8400-e29b",
  "status": "PENDING",
  "estimated_delivery_seconds": 5,
  "idempotency_key": "<echoed back>"
}
```

**Design Notes:**
- `202 Accepted` (not `200 OK`) — delivery is asynchronous; acceptance ≠ delivery
- `Idempotency-Key` header is required for all write operations
- `template_version` is optional — pinning is recommended for stable messages
- `channels_override` bypasses preference evaluation — use only for critical system messages
- `expires_at` prevents stale delivery of time-sensitive content (OTP codes)

---

### Submit Batch Notification

```
POST /api/v1/notifications/batch
Authorization: Bearer <service-jwt>
Idempotency-Key: <producer-generated UUID>

Request Body:
{
  "name": "Summer Sale Launch",
  "category": "MARKETING",
  "priority": "NORMAL",
  "template_id": "summer-sale-v2",
  "template_version": 1,
  "global_variables": {
    "sale_end_date": "May 31, 2026"
  },
  "segment_id": "seg-active-last-30-days",
  "scheduled_at": "2026-05-20T09:00:00Z",
  "throttle_rps": 5000               // max notifications/sec for this campaign
}

Response: 202 Accepted
{
  "batch_id": "bat-abc123",
  "status": "SCHEDULED",
  "scheduled_at": "2026-05-20T09:00:00Z",
  "estimated_recipients": 4200000
}
```

---

### Cancel Scheduled Notification

```
DELETE /api/v1/notifications/{notification_id}
Authorization: Bearer <service-jwt>

Response: 200 OK
{
  "notification_id": "ntf-550e8400",
  "status": "CANCELLED",
  "cancelled_at": "2026-05-17T08:30:00Z"
}
```
**Notes:** Only works if status is PENDING or SCHEDULED. Returns 409 Conflict if already dispatched.

---

## Notification Status API

### Get Notification Status

```
GET /api/v1/notifications/{notification_id}
Authorization: Bearer <service-jwt>

Response: 200 OK
{
  "notification_id": "ntf-550e8400",
  "status": "PARTIALLY_DELIVERED",
  "created_at": "2026-05-17T08:00:00Z",
  "category": "TRANSACTIONAL",
  "priority": "HIGH",
  "recipient_user_id": "usr-123e4567",
  "delivery_attempts": [
    {
      "channel": "EMAIL",
      "status": "DELIVERED",
      "provider": "sendgrid",
      "provider_message_id": "SG-abc123",
      "attempted_at": "2026-05-17T08:00:03Z",
      "delivered_at": "2026-05-17T08:00:05Z"
    },
    {
      "channel": "PUSH",
      "status": "FAILED",
      "attempt_number": 3,
      "failure_reason": "DEVICE_TOKEN_EXPIRED",
      "next_retry_at": null
    }
  ]
}
```

---

### Register Webhook for Status Updates

```
POST /api/v1/notifications/{notification_id}/webhooks
Authorization: Bearer <service-jwt>

{
  "callback_url": "https://order-service.internal/callbacks/notification",
  "events": ["DELIVERED", "FAILED"],
  "secret": "hmac-signing-secret"
}

Response: 201 Created
{
  "webhook_id": "wh-xyz",
  "notification_id": "ntf-550e8400",
  "registered_at": "2026-05-17T08:00:00Z"
}
```

---

## User Preference API

### Get User Preferences

```
GET /api/v1/users/{user_id}/notification-preferences
Authorization: Bearer <user-jwt>

Response: 200 OK
{
  "user_id": "usr-123e4567",
  "preferences": [
    {
      "channel": "EMAIL",
      "category": "MARKETING",
      "opted_in": true
    },
    {
      "channel": "SMS",
      "category": "TRANSACTIONAL",
      "opted_in": true
    },
    {
      "channel": "PUSH",
      "category": "PRODUCT_UPDATE",
      "opted_in": false
    }
  ],
  "quiet_hours": {
    "start": "22:00",
    "end": "08:00",
    "timezone": "Asia/Kolkata"
  }
}
```

---

### Update Preferences

```
PATCH /api/v1/users/{user_id}/notification-preferences
Authorization: Bearer <user-jwt>

{
  "preferences": [
    {
      "channel": "EMAIL",
      "category": "MARKETING",
      "opted_in": false
    }
  ],
  "quiet_hours": {
    "start": "23:00",
    "end": "07:00",
    "timezone": "Asia/Kolkata"
  }
}

Response: 200 OK
{
  "updated": ["EMAIL:MARKETING"],
  "ignored": []
}
```

---

## In-App Inbox API

### List Inbox Notifications (Paginated)

```
GET /api/v1/users/{user_id}/inbox?limit=20&cursor=<opaque_cursor>&filter=unread
Authorization: Bearer <user-jwt>

Response: 200 OK
{
  "items": [
    {
      "inbox_item_id": "inb-001",
      "notification_id": "ntf-550e8400",
      "title": "Your order has shipped!",
      "body": "Order ORD-789 is on its way.",
      "action_url": "/orders/ORD-789",
      "is_read": false,
      "created_at": "2026-05-17T08:00:00Z"
    }
  ],
  "cursor": "eyJpZCI6ImluYi0wMjAifQ==",
  "has_more": true,
  "unread_count": 5
}
```

**Pagination Strategy:** Cursor-based (opaque token encoding last item ID + timestamp). Avoids the offset drift problem when new notifications arrive during pagination.

---

### Mark as Read

```
PATCH /api/v1/users/{user_id}/inbox/{inbox_item_id}
Authorization: Bearer <user-jwt>

{ "is_read": true }

Response: 200 OK
{ "inbox_item_id": "inb-001", "is_read": true, "read_at": "2026-05-17T09:15:00Z" }
```

### Mark All as Read

```
POST /api/v1/users/{user_id}/inbox/read-all
Authorization: Bearer <user-jwt>

Response: 200 OK
{ "marked_read": 5 }
```

---

## Unsubscribe API

### One-Click Unsubscribe (Public, No Auth Required)

```
GET /unsubscribe?token=<unsubscribe_token>

Response: 200 OK
{
  "message": "You have been unsubscribed from marketing emails.",
  "channel": "EMAIL",
  "category": "MARKETING"
}
```

**Design Notes:**
- Token is single-use and hashed in DB — exposure in email link doesn't compromise account
- Token encodes user_id + channel + category + expiry (signed, not encrypted)
- CAN-SPAM requires one-click unsubscribe within 10 business days — this endpoint honors it immediately
- `List-Unsubscribe-Post` header in emails points to a corresponding POST endpoint for email client integration

---

## Error Handling Standards

### Error Response Format

```json
{
  "error": {
    "code": "TEMPLATE_NOT_FOUND",
    "message": "Template 'order-confirmed' version 99 does not exist",
    "request_id": "req-abc123",
    "timestamp": "2026-05-17T08:00:00Z",
    "details": {
      "template_id": "order-confirmed",
      "requested_version": 99,
      "latest_active_version": 3
    }
  }
}
```

### Error Code Taxonomy

| HTTP Status | Error Code | Meaning |
|-------------|-----------|---------|
| 400 | INVALID_REQUEST | Missing or malformed fields |
| 400 | VARIABLE_SCHEMA_MISMATCH | Submitted variables don't match template schema |
| 409 | DUPLICATE_IDEMPOTENCY_KEY | Request already processed |
| 409 | NOTIFICATION_NOT_CANCELLABLE | Already dispatched or delivered |
| 404 | TEMPLATE_NOT_FOUND | Template ID/version not found |
| 404 | USER_NOT_FOUND | Recipient user doesn't exist |
| 422 | RECIPIENT_UNSUBSCRIBED | User opted out of this category/channel |
| 429 | RATE_LIMIT_EXCEEDED | Producer submitting too fast |
| 503 | SERVICE_UNAVAILABLE | System at capacity, retry with backoff |

---

## Idempotency Strategy

- All write operations require `Idempotency-Key` header (UUID, max 255 chars)
- Key is stored in Redis with the response payload for 24 hours
- If the same key is received while the first is in-flight: return 409 with `status: PROCESSING`
- If the same key is received after completion: return the original response (200/202) with a `X-Idempotent-Replay: true` header
- Keys expire after 24 hours — producers must not reuse keys across days for different requests

---

## Retry Strategy for API Consumers

Producers should implement exponential backoff when receiving:
- `503 SERVICE_UNAVAILABLE`
- `429 RATE_LIMIT_EXCEEDED`
- Network timeouts

The API returns `Retry-After` header for 429 responses. Recommended backoff: 1s, 2s, 4s, 8s, max 60s.

---

## API Evolution Strategy

| Change Type | Strategy |
|-------------|---------|
| Add optional field | Additive, no version bump |
| Add new endpoint | Additive, no version bump |
| Remove field | Deprecate with `X-Field-Deprecated` header for 1 major version cycle |
| Change field semantics | New major version (`/v2/`) |
| Remove endpoint | Sunset header + 12-month notice |

---

## Rate Limiting

| API Group | Limit | Scope |
|-----------|-------|-------|
| Notification Submission | 10,000 req/min | Per producer service |
| Batch Submission | 10 req/min | Per producer service |
| Status API | 60 req/min | Per producer |
| Preference API | 100 req/min | Per user |
| Inbox API | 200 req/min | Per user |

---

## Interview Discussion Points

- Why return `202 Accepted` instead of waiting for delivery confirmation?
- When would a producer legitimately use `channels_override`? What are the abuse risks?
- How do you handle backward compatibility when adding a new notification `category` enum value?
- Why is cursor-based pagination chosen over offset-based for the inbox?
- How do you prevent a noisy producer from starving other producers at the API layer?
- What are the GDPR implications of storing variables (which may contain PII) on the notification record?
