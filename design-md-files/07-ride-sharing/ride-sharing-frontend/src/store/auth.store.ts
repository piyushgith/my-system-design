import { create } from 'zustand'
import type { UserRole } from '@/types/api/common.types'

export type AuthMode = 'dev-shortcut' | 'otp'

type AuthState = {
  mode: AuthMode | null
  uid: string | null
  role: UserRole | null
  accessToken: string | null
  refreshToken: string | null
  loginDev: (shortcut: 'rider' | 'driver') => void
  loginOtp: (userId: string, role: UserRole, accessToken: string, refreshToken: string) => void
  logout: () => void
  isAuthenticated: () => boolean
  getAuthHeaders: () => Record<string, string>
}

const STORAGE_KEY = 'ride-auth'

const loadPersisted = (): Pick<AuthState, 'mode' | 'uid' | 'role' | 'accessToken' | 'refreshToken'> => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { mode: null, uid: null, role: null, accessToken: null, refreshToken: null }
    return JSON.parse(raw) as Pick<AuthState, 'mode' | 'uid' | 'role' | 'accessToken' | 'refreshToken'>
  } catch {
    return { mode: null, uid: null, role: null, accessToken: null, refreshToken: null }
  }
}

const persist = (state: Pick<AuthState, 'mode' | 'uid' | 'role' | 'accessToken' | 'refreshToken'>) => {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
}

export const useAuthStore = create<AuthState>((set, get) => ({
  ...loadPersisted(),

  loginDev: (shortcut) => {
    const role: UserRole = shortcut === 'rider' ? 'RIDER' : 'DRIVER'
    const next = { mode: 'dev-shortcut' as AuthMode, uid: shortcut, role, accessToken: null, refreshToken: null }
    persist(next)
    set(next)
  },

  loginOtp: (userId, role, accessToken, refreshToken) => {
    const next = { mode: 'otp' as AuthMode, uid: userId, role, accessToken, refreshToken }
    persist(next)
    set(next)
  },

  logout: () => {
    localStorage.removeItem(STORAGE_KEY)
    set({ mode: null, uid: null, role: null, accessToken: null, refreshToken: null })
  },

  isAuthenticated: () => {
    const { uid, role } = get()
    return Boolean(uid && role)
  },

  getAuthHeaders: (): Record<string, string> => {
    const { uid, role, mode } = get()
    if (!uid || !role) return {}
    const headers: Record<string, string> = { 'X-Uid': uid }
    if (mode !== 'dev-shortcut') {
      headers['X-Role'] = role
    }
    return headers
  },
}))
