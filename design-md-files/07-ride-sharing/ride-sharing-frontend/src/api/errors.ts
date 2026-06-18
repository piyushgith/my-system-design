import axios from 'axios'
import type { ApiErrorBody } from '@/types/api/common.types'

export class ApiError extends Error {
  code: string
  status: number
  requestId?: string
  details: Record<string, unknown>

  constructor(
    message: string,
    code: string,
    status: number,
    details: Record<string, unknown> = {},
    requestId?: string,
  ) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.details = details
    this.requestId = requestId
  }
}

export const parseApiError = (error: unknown): ApiError => {
  if (axios.isAxiosError(error)) {
    const status = error.response?.status ?? 0
    const body = error.response?.data as ApiErrorBody | undefined
    if (body?.error) {
      return new ApiError(
        body.error.message,
        body.error.code,
        status,
        body.error.details,
        body.error.request_id,
      )
    }
    return new ApiError(error.message || 'Network error', 'NETWORK_ERROR', status)
  }
  if (error instanceof ApiError) {
    return error
  }
  return new ApiError('Unexpected error', 'UNKNOWN', 0)
}

export const isApiErrorCode = (error: unknown, code: string): boolean =>
  error instanceof ApiError && error.code === code

export const getApiErrorDetail = (error: unknown, key: string): string | undefined => {
  if (!(error instanceof ApiError)) return undefined
  const value = error.details[key]
  return typeof value === 'string' ? value : undefined
}
