import { create } from 'zustand'
import type { AuthResponse } from '@/types'

interface AuthState {
  user: AuthResponse | null
  isAuthenticated: boolean
  login: (auth: AuthResponse) => void
  logout: () => void
}

const STORAGE_KEY = 'crm_user'

function loadUser(): AuthResponse | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as AuthResponse) : null
  } catch {
    return null
  }
}

export const useAuthStore = create<AuthState>((set) => {
  const initialUser = loadUser()
  return {
    user: initialUser,
    isAuthenticated: !!initialUser,

    login: (auth) => {
      localStorage.setItem('crm_token', auth.token)
      localStorage.setItem(STORAGE_KEY, JSON.stringify(auth))
      set({ user: auth, isAuthenticated: true })
    },

    logout: () => {
      localStorage.removeItem('crm_token')
      localStorage.removeItem(STORAGE_KEY)
      set({ user: null, isAuthenticated: false })
    },
  }
})
