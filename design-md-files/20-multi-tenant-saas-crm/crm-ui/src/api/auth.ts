import { apiClient } from './client'
import type { AuthResponse, LoginRequest, RegisterRequest } from '@/types'

export const authApi = {
  login: (req: LoginRequest) =>
    apiClient.post<AuthResponse>('/v1/auth/login', req).then((r) => r.data),

  register: (req: RegisterRequest) =>
    apiClient.post<AuthResponse>('/v1/auth/register', req).then((r) => r.data),
}
