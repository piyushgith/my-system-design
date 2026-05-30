// ─── Enums (must match backend exactly) ──────────────────────────────────────

export type Channel = 'EMAIL' | 'SMS' | 'PUSH' | 'IN_APP'

export type Category =
  | 'TRANSACTIONAL'
  | 'MARKETING'
  | 'PRODUCT_UPDATE'
  | 'SECURITY'

export type Priority = 'CRITICAL' | 'HIGH' | 'NORMAL' | 'LOW'

export type NotificationStatus =
  | 'PENDING'
  | 'DISPATCHING'
  | 'DELIVERED'
  | 'PARTIALLY_DELIVERED'
  | 'FAILED'
  | 'CANCELLED'
  | 'EXPIRED'

export type DeliveryStatus =
  | 'PENDING'
  | 'IN_FLIGHT'
  | 'DELIVERED'
  | 'FAILED'
  | 'BOUNCED'

// ─── Request DTOs ─────────────────────────────────────────────────────────────

export interface ProducerContext {
  service: string
  traceId: string
}

export interface SubmitNotificationRequest {
  category: Category
  priority: Priority
  recipientUserId: string
  templateId: string
  templateVersion?: number
  variables: Record<string, string>
  channelsOverride?: Channel[]
  scheduledAt?: string    // ISO-8601 instant, null = immediate
  expiresAt: string       // ISO-8601 instant, required
  producerContext: ProducerContext
}

export interface UpdatePreferencesRequest {
  preferences: PreferenceUpdate[]
}

export interface PreferenceUpdate {
  channel: Channel
  category: Category
  optedIn: boolean
  quietHoursStart?: string   // HH:mm
  quietHoursEnd?: string     // HH:mm
  timezone?: string
}

export interface CreateTemplateRequest {
  templateId: string
  channel: Channel
  locale: string
  subject?: string          // email only
  bodyHtml?: string         // email HTML
  bodyText?: string         // plain text / SMS
  pushTitle?: string        // push only
  pushBody?: string         // push only
  variablesSchema: Record<string, string>
  createdBy: string
}

// ─── Response DTOs ────────────────────────────────────────────────────────────

export interface SubmitNotificationResponse {
  notificationId: string
  idempotencyKey: string
  status: NotificationStatus
}

export interface DeliveryAttempt {
  attemptId: string
  notificationId: string
  channel: Channel
  provider: string
  providerMessageId?: string
  status: DeliveryStatus
  attemptNumber: number
  attemptedAt: string
  deliveredAt?: string
  failureReason?: string
  failureCode?: string
  nextRetryAt?: string
}

export interface NotificationStatusResponse {
  notificationId: string
  idempotencyKey: string
  category: Category
  priority: Priority
  templateId: string
  templateVersion?: number
  recipientUserId: string
  batchId?: string
  variables: Record<string, string>
  channelsOverride?: Channel[]
  status: NotificationStatus
  scheduledAt?: string
  expiresAt?: string
  createdAt: string
  dispatchedAt?: string
  completedAt?: string
  producerService: string
  producerTraceId: string
  deliveryAttempts: DeliveryAttempt[]
}

export interface UserNotificationPreference {
  userId: string
  channel: Channel
  category: Category
  optedIn: boolean
  quietHoursStart?: string
  quietHoursEnd?: string
  timezone: string
  hardUnsubscribed: boolean
  hardUnsubscribedAt?: string
  updatedAt: string
}

export interface InAppInboxItem {
  inboxItemId: string
  notificationId: string
  userId: string
  title: string
  body: string
  actionUrl?: string
  imageUrl?: string
  isRead: boolean
  readAt?: string
  createdAt: string
  expiresAt?: string
}

export interface Template {
  templateId: string
  version: number
  channel: Channel
  locale: string
  subject?: string
  bodyHtml?: string
  bodyText?: string
  pushTitle?: string
  pushBody?: string
  variablesSchema: Record<string, string>
  isActive: boolean
  createdBy: string
  createdAt: string
  deprecatedAt?: string
}

// Spring Page wrapper
export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  size: number
  number: number       // current page (0-based)
  first: boolean
  last: boolean
  empty: boolean
}

export interface ErrorResponse {
  error: {
    code: string
    message: string
    requestId: string
    timestamp: string
    details?: Record<string, string>
  }
}

// ─── Auth (local concept — backend uses platform JWT) ─────────────────────────

export interface AuthUser {
  userId: string
  name: string
  role: 'admin' | 'producer' | 'user'
  token: string
}
