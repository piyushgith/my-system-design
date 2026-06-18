import { describe, it, expect, vi } from 'vitest'
import { createIdempotencyKey } from '@/utils/idempotency'

describe('createIdempotencyKey', () => {
  it('returns a UUID string', () => {
    vi.stubGlobal('crypto', { randomUUID: () => 'test-uuid-1234' })
    expect(createIdempotencyKey()).toBe('test-uuid-1234')
  })
})
