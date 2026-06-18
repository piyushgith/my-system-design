import { apiClient } from '@/api/client'
import type {
  OtpRequestPayload,
  OtpRequestResponse,
  OtpVerifyPayload,
  OtpVerifyResponse,
} from '@/types/api/auth.types'

export const authService = {
  requestOtp: async (payload: OtpRequestPayload): Promise<OtpRequestResponse> => {
    const { data } = await apiClient.post<OtpRequestResponse>('/v1/auth/otp/request', {
      phoneNumber: payload.phoneNumber,
    })
    return data
  },

  verifyOtp: async (payload: OtpVerifyPayload): Promise<OtpVerifyResponse> => {
    const { data } = await apiClient.post<OtpVerifyResponse>('/v1/auth/otp/verify', {
      otpRequestId: payload.otpRequestId,
      otpCode: payload.otpCode,
      userType: payload.userType,
    })
    return data
  },
}
