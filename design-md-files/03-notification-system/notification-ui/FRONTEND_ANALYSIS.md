# Backend API Analysis — Notification Service

## Endpoints Mapped

### Notifications
| Method | Path | Frontend usage |
|---|---|---|
| POST | `/api/v1/notifications` | `SendNotificationPage` — with `Idempotency-Key` header |
| GET | `/api/v1/notifications/{notificationId}` | `NotificationDetailPage` — polled every 3s until terminal |
| DELETE | `/api/v1/notifications/{notificationId}` | Cancel button in `NotificationDetailPage` |

### Preferences
| Method | Path | Frontend usage |
|---|---|---|
| GET | `/api/v1/users/{userId}/notification-preferences` | `PreferencesPage` |
| PATCH | `/api/v1/users/{userId}/notification-preferences` | `PreferenceToggle` onChange |
| POST | `/api/v1/preferences/unsubscribe` | `UnsubscribePage` (public, token in query param) |

### Inbox
| Method | Path | Frontend usage |
|---|---|---|
| GET | `/api/v1/users/{userId}/inbox` | `InboxPage` — Spring `Page<T>` offset pagination |
| PUT | `/api/v1/users/{userId}/inbox/{inboxItemId}/read` | `InboxItemCard` mark-read button |
| POST | `/api/v1/users/{userId}/inbox/read-all` | `InboxPage` mark-all-read button |

### Templates
| Method | Path | Frontend usage |
|---|---|---|
| POST | `/api/v1/templates` | `CreateTemplatePage` |
| GET | `/api/v1/templates/{templateId}` | `TemplatesPage` lookup |
| DELETE | `/api/v1/templates/{templateId}/versions/{version}` | Deprecate button in `TemplatesPage` |

## Key Backend Contracts

### `SubmitNotificationRequest` (POST /notifications)
- `Idempotency-Key` header required (UUID, per-request)
- `expiresAt` ISO-8601 required; `scheduledAt` optional
- `channelsOverride` absent = use user preferences; present = force channels
- `producerContext.service` and `producerContext.traceId` required for observability
- Response: 202 Accepted with `notificationId` — async processing

### `NotificationStatusResponse` (GET /notifications/{id})
- Polling required until terminal: DELIVERED | PARTIALLY_DELIVERED | FAILED | CANCELLED | EXPIRED
- `deliveryAttempts[]` grows as dispatch progresses
- Non-terminal statuses: PENDING | DISPATCHING

### `UserNotificationPreference` (GET /preferences)
- `hardUnsubscribed: true` = provider bounce — cannot re-enable from UI (backend rejects)
- `optedIn` is the toggle field (not `enabled`)
- `quietHoursStart`/`quietHoursEnd` in HH:mm format

### `InAppInboxItem` (GET /inbox)
- Pagination: Spring `Page<T>` offset-based (`page`, `size`, `totalElements`, `totalPages`)
- `isRead` boolean (not `read`)
- `unreadOnly` query param for filtering

### `Template` (GET /templates/{templateId})
- Per-channel: one template entry per channel (not multi-channel)
- `variablesSchema: Record<string, string>` — key is variable name, value is description
- `isActive` boolean; `deprecatedAt` timestamp (null when active)
- No `name`/`description`/`supportedChannels` — these exist in domain model but not response DTO

### `CreateTemplateRequest` (POST /templates)
- Channel-specific body fields: `subject`/`bodyHtml` for EMAIL, `bodyText` for SMS/IN_APP, `pushTitle`/`pushBody` for PUSH
- `variablesSchema` required (can be empty object)
- `createdBy` = userId of creator

## Type Discrepancies Found During Implementation

| Issue | Resolution |
|---|---|
| `Template` has no `name` field in DTO | Use `templateId` as display title |
| `Template` has no `supportedChannels` array | Single `channel` field per template entry |
| `PreferenceUpdate.enabled` does not exist | Correct field is `optedIn` |
| `InAppInboxItem.read` does not exist | Correct field is `isRead` |
| `useTemplate` takes 1 arg (templateId only) | Version lookup not exposed in hook — lookup by ID only |
| `axiosInstance` not a named export | Default export named `apiClient` |

All discrepancies resolved against actual `src/types/index.ts` — the source of truth.
