import { describe, it, expect } from 'vitest'
import { otpPhoneSchema, otpVerifySchema } from '@/features/auth/schemas/otp.schema'

describe('otp schemas', () => {
  it('validates phone number', () => {
    const result = otpPhoneSchema.safeParse({ phoneNumber: '+919900000001' })
    expect(result.success).toBe(true)
  })

  it('rejects short phone', () => {
    const result = otpPhoneSchema.safeParse({ phoneNumber: '123' })
    expect(result.success).toBe(false)
  })

  it('validates 6-digit OTP', () => {
    const result = otpVerifySchema.safeParse({
      otpRequestId: 'abc',
      otpCode: '123456',
    })
    expect(result.success).toBe(true)
  })
})
