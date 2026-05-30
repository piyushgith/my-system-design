import { describe, it, expect } from 'vitest'
import { AxiosError } from 'axios'
import { extractErrorMessage, extractErrorCode } from '../../utils/errors'

function makeAxiosError(data: unknown, message = 'Request failed'): AxiosError {
  const err = new AxiosError(message)
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  err.response = { data, status: 400, statusText: 'Bad Request', headers: {}, config: {} as any } as any
  return err
}

describe('extractErrorMessage', () => {
  it('extracts message from backend ErrorResponse shape', () => {
    const err = makeAxiosError({ error: { message: 'Template not found', code: 'NOT_FOUND' } })
    expect(extractErrorMessage(err)).toBe('Template not found')
  })

  it('falls back to axios message when no structured body', () => {
    const err = makeAxiosError(null, 'Network Error')
    err.response = undefined
    expect(extractErrorMessage(err)).toBe('Network Error')
  })

  it('falls back to generic Error message', () => {
    expect(extractErrorMessage(new Error('boom'))).toBe('boom')
  })

  it('returns fallback for non-error values', () => {
    expect(extractErrorMessage('string error')).toBe('An unexpected error occurred')
  })
})

describe('extractErrorCode', () => {
  it('extracts code from backend shape', () => {
    const err = makeAxiosError({ error: { code: 'VALIDATION_ERROR', message: '' } })
    expect(extractErrorCode(err)).toBe('VALIDATION_ERROR')
  })

  it('returns null when no code present', () => {
    expect(extractErrorCode(new Error('oops'))).toBeNull()
  })

  it('returns null when response has no error body', () => {
    const err = makeAxiosError(null)
    expect(extractErrorCode(err)).toBeNull()
  })
})
