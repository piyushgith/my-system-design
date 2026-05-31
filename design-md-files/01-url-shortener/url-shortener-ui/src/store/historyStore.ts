import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { HistoryEntry } from '@/types/api'

const MAX_HISTORY = 50

interface HistoryState {
  entries: HistoryEntry[]
  add: (entry: HistoryEntry) => void
  remove: (shortCode: string) => void
  clear: () => void
}

export const useHistoryStore = create<HistoryState>()(
  persist(
    (set) => ({
      entries: [],
      add: (entry) =>
        set((state) => ({
          entries: [entry, ...state.entries].slice(0, MAX_HISTORY),
        })),
      remove: (shortCode) =>
        set((state) => ({
          entries: state.entries.filter((e) => e.shortCode !== shortCode),
        })),
      clear: () => set({ entries: [] }),
    }),
    {
      name: 'url-shortener-history',
    }
  )
)
