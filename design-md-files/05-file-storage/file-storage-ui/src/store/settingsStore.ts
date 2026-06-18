import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface SettingsState {
  ownerId: string
  preferPresigned: boolean
  setOwnerId: (ownerId: string) => void
  setPreferPresigned: (preferPresigned: boolean) => void
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      ownerId: '',
      preferPresigned: true,
      setOwnerId: (ownerId) => set({ ownerId }),
      setPreferPresigned: (preferPresigned) => set({ preferPresigned }),
    }),
    { name: 'vault:settings' },
  ),
)
