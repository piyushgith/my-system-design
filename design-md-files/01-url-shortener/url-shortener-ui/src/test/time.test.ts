import { describe, it, expect } from 'vitest'
import { formatRelativeTime, formatDateTime } from '@/utils/time'

describe('formatRelativeTime', () => {
  it('returns Expired for past dates', () => {
    const past = new Date(Date.now() - 1000).toISOString()
    expect(formatRelativeTime(past)).toBe('Expired')
  })

  it('returns days for > 1 day remaining', () => {
    const future = new Date(Date.now() + 2 * 86400 * 1000).toISOString()
    expect(formatRelativeTime(future)).toMatch(/^2 days$/)
  })

  it('returns hours for < 1 day remaining', () => {
    const future = new Date(Date.now() + 3 * 3600 * 1000).toISOString()
    expect(formatRelativeTime(future)).toMatch(/^3 hours$/)
  })

  it('uses singular for 1 day', () => {
    const future = new Date(Date.now() + 1 * 86400 * 1000 + 1000).toISOString()
    expect(formatRelativeTime(future)).toBe('1 day')
  })
})

describe('formatDateTime', () => {
  it('returns a non-empty string', () => {
    expect(formatDateTime('2024-06-15T10:30:00Z')).toBeTruthy()
  })
})
