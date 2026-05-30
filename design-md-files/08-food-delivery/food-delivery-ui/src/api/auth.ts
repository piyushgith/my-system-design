import { apiClient } from './axios'
import type { AuthResponse, SendOtpRequest, VerifyOtpRequest } from '../types/auth'

export const authApi = {
  sendOtp: (data: SendOtpRequest) =>
    apiClient.post<void>('/auth/send-otp', data),

  verifyOtp: (data: VerifyOtpRequest) =>
    apiClient.post<AuthResponse>('/auth/verify-otp', data),
}
