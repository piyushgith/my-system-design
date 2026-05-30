export interface AuthResponse {
  accessToken: string
  userId: string
  name: string
  phone: string
}

export interface SendOtpRequest {
  phone: string
}

export interface VerifyOtpRequest {
  phone: string
  otp: string
}

export type UserRole = 'CUSTOMER' | 'RESTAURANT_OWNER' | 'DELIVERY_PARTNER' | 'ADMIN'
