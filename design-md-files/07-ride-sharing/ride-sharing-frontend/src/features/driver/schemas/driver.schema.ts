import { z } from 'zod'

export const goOnlineSchema = z.object({
  vehicleId: z.string().uuid(),
  cityId: z.string().uuid(),
  lat: z.number(),
  lng: z.number(),
})

export const startTripSchema = z.object({
  otp: z.string().min(4, 'Enter the 4-digit OTP'),
})

export const completeTripSchema = z.object({
  finalLat: z.number(),
  finalLng: z.number(),
})

export type GoOnlineForm = z.infer<typeof goOnlineSchema>
export type StartTripForm = z.infer<typeof startTripSchema>
export type CompleteTripForm = z.infer<typeof completeTripSchema>
