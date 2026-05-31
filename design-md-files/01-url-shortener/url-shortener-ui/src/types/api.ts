// Mirrors backend: CreateUrlRequest.java
export interface CreateUrlRequest {
  longUrl: string
  alias?: string
  ttl?: number // seconds, positive integer
}

// Mirrors backend: CreateUrlResponse.java
export interface CreateUrlResponse {
  shortUrl: string
  shortCode: string
  longUrl: string
  createdAt: string // ISO-8601 Instant
  expiresAt: string | null
}

// Mirrors backend: ErrorResponse.java
export interface ApiError {
  error: {
    code: ErrorCode
    message: string
    field: string | null
    timestamp: string
  }
}

export type ErrorCode =
  | 'INVALID_URL'
  | 'INVALID_ALIAS'
  | 'RESERVED_ALIAS'
  | 'ALIAS_CONFLICT'
  | 'VALIDATION_ERROR'
  | 'SHORT_CODE_GENERATION_FAILED'
  | 'SHORT_URL_NOT_FOUND'
  | 'SHORT_URL_EXPIRED'
  | 'NETWORK_ERROR'

// Stored in local history
export interface HistoryEntry extends CreateUrlResponse {
  savedAt: string // ISO-8601, set by frontend
}
