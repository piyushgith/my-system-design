import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import type { UserRole } from '../types'

interface AuthState {
  username: string | null
  password: string | null
  role: UserRole | null
  cifId: string | null

  login: (username: string, password: string, role: UserRole) => void
  logout: () => void
  getAuthHeader: () => string | null
  isTeller: () => boolean
  isCustomer: () => boolean
  isAuthenticated: () => boolean
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      username: null,
      password: null,
      role: null,
      cifId: null,

      login: (username, password, role) => {
        set({
          username,
          password,
          role,
          cifId: role === 'CUSTOMER' ? username : null,
        })
      },

      logout: () => {
        set({ username: null, password: null, role: null, cifId: null })
      },

      getAuthHeader: () => {
        const { username, password } = get()
        if (!username || !password) return null
        return 'Basic ' + btoa(`${username}:${password}`)
      },

      isTeller: () => get().role === 'TELLER',
      isCustomer: () => get().role === 'CUSTOMER',
      isAuthenticated: () => get().role !== null,
    }),
    {
      name: 'banking-auth',
      storage: createJSONStorage(() => sessionStorage),
    }
  )
)
