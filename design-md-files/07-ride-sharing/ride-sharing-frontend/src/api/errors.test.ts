import { describe, it, expect } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { parseApiError, ApiError } from '@/api/errors'

describe('parseApiError', () => {
  it('parses backend error envelope', () => {
    const error = new AxiosError('Request failed', '409', undefined, undefined, {
      status: 409,
      statusText: 'Conflict',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: {
        error: {
          code: 'TRIP_ALREADY_ACTIVE',
          message: 'You already have an active trip',
          details: {},
          request_id: 'req_abc123',
          timestamp: '2026-06-18T00:00:00Z',
        },
      },
    })

    const parsed = parseApiError(error)
    expect(parsed).toBeInstanceOf(ApiError)
    expect(parsed.code).toBe('TRIP_ALREADY_ACTIVE')
    expect(parsed.status).toBe(409)
    expect(parsed.requestId).toBe('req_abc123')
  })
})
