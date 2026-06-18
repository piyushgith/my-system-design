import { z } from 'zod'

export const otpPhoneSchema = z.object({
  phoneNumber: z.string().min(10, 'Enter a valid phone number'),
})

export const otpVerifySchema = z.object({
  otpRequestId: z.string().min(1),
  otpCode: z.string().length(6, 'OTP must be 6 digits'),
  userType: z.enum(['RIDER', 'DRIVER']).optional(),
})

export type OtpPhoneForm = z.infer<typeof otpPhoneSchema>
export type OtpVerifyForm = z.infer<typeof otpVerifySchema>
