import { describe, it, expect, beforeEach } from 'vitest'
import { useHistoryStore } from '@/store/historyStore'
import type { HistoryEntry } from '@/types/api'

function makeEntry(shortCode: string): HistoryEntry {
  return {
    shortCode,
    shortUrl: `http://localhost:8080/${shortCode}`,
    longUrl: 'https://example.com/some/very/long/path',
    createdAt: '2024-01-01T00:00:00Z',
    expiresAt: null,
    savedAt: '2024-01-01T00:00:00Z',
  }
}

describe('useHistoryStore', () => {
  beforeEach(() => {
    useHistoryStore.setState({ entries: [] })
  })

  it('adds entry to front of list', () => {
    useHistoryStore.getState().add(makeEntry('abc1234'))
    useHistoryStore.getState().add(makeEntry('xyz5678'))
    const { entries } = useHistoryStore.getState()
    expect(entries[0].shortCode).toBe('xyz5678')
    expect(entries[1].shortCode).toBe('abc1234')
  })

  it('removes entry by shortCode', () => {
    useHistoryStore.getState().add(makeEntry('abc1234'))
    useHistoryStore.getState().add(makeEntry('xyz5678'))
    useHistoryStore.getState().remove('abc1234')
    const { entries } = useHistoryStore.getState()
    expect(entries).toHaveLength(1)
    expect(entries[0].shortCode).toBe('xyz5678')
  })

  it('clears all entries', () => {
    useHistoryStore.getState().add(makeEntry('abc1234'))
    useHistoryStore.getState().add(makeEntry('xyz5678'))
    useHistoryStore.getState().clear()
    expect(useHistoryStore.getState().entries).toHaveLength(0)
  })

  it('caps history at 50 entries', () => {
    for (let i = 0; i < 55; i++) {
      useHistoryStore.getState().add(makeEntry(`code${i.toString().padStart(3, '0')}`))
    }
    expect(useHistoryStore.getState().entries).toHaveLength(50)
  })
})
