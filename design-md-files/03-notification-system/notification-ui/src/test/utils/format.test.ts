import { describe, it, expect } from 'vitest'
import { truncate } from '../../utils/format'

describe('truncate', () => {
  it('returns string unchanged when shorter than max', () => {
    expect(truncate('hello', 10)).toBe('hello')
  })

  it('truncates and appends ellipsis when over max', () => {
    expect(truncate('hello world', 5)).toBe('hello…')
  })

  it('exact length is not truncated', () => {
    expect(truncate('hello', 5)).toBe('hello')
  })
})
