import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { UserRole } from '../types/auth'
import { extractRole } from '../utils/jwt'
import { setToken, clearToken } from '../api/axios'

interface AuthState {
  token: string | null
  userId: string | null
  name: string | null
  phone: string | null
  role: UserRole | null
  isAuthenticated: boolean
  login: (token: string, userId: string, name: string, phone: string) => void
  logout: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      userId: null,
      name: null,
      phone: null,
      role: null,
      isAuthenticated: false,

      login: (token, userId, name, phone) => {
        let role: UserRole | null = null
        try {
          role = extractRole(token)
        } catch {
          // malformed token — store null role, server will reject authed requests
        }
        setToken(token)
        set({ token, userId, name, phone, role, isAuthenticated: true })
      },

      logout: () => {
        clearToken()
        set({ token: null, userId: null, name: null, phone: null, role: null, isAuthenticated: false })
      },
    }),
    { name: 'fd_auth' }
  )
)
