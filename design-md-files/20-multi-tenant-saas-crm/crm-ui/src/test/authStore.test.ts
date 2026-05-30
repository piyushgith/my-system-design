import { describe, it, expect, beforeEach } from 'vitest'
import { useAuthStore } from '@/store/authStore'
import type { AuthResponse } from '@/types'

const mockAuth: AuthResponse = {
  token: 'test-token',
  userId: '123e4567-e89b-12d3-a456-426614174000',
  email: 'test@example.com',
  fullName: 'Test User',
  role: 'SALES_REP',
}

describe('authStore', () => {
  beforeEach(() => {
    localStorage.clear()
    useAuthStore.setState({ user: null, isAuthenticated: false })
  })

  it('login stores user and token', () => {
    useAuthStore.getState().login(mockAuth)
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
    expect(useAuthStore.getState().user?.email).toBe('test@example.com')
    expect(localStorage.getItem('crm_token')).toBe('test-token')
  })

  it('logout clears user and token', () => {
    useAuthStore.getState().login(mockAuth)
    useAuthStore.getState().logout()
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(useAuthStore.getState().user).toBeNull()
    expect(localStorage.getItem('crm_token')).toBeNull()
  })
})
