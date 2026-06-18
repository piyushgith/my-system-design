import type { UserRole } from './common.types'

export type OtpRequestPayload = {
  phoneNumber: string
}

export type OtpVerifyPayload = {
  otpRequestId: string
  otpCode: string
  userType?: UserRole
}

export type OtpRequestResponse = {
  otp_request_id: string
  expires_in_seconds: number
  dev_hint?: string
}

export type OtpVerifyResponse = {
  access_token: string
  refresh_token: string
  user_type: UserRole
  user_id: string
}
