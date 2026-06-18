import { z } from 'zod'

export const fareEstimateSchema = z.object({
  pickupLat: z.number(),
  pickupLng: z.number(),
  destinationLat: z.number(),
  destinationLng: z.number(),
  pickupAddress: z.string().min(1, 'Pickup address required'),
  destinationAddress: z.string().min(1, 'Destination address required'),
  vehicleType: z.enum(['ECONOMY', 'PREMIUM', 'SUV', 'AUTO', 'BIKE']),
  cityId: z.string().uuid(),
})

export const rateTripSchema = z.object({
  score: z.number().int().min(1).max(5),
  comment: z.string().max(500).optional(),
})

export type FareEstimateForm = z.infer<typeof fareEstimateSchema>
export type RateTripForm = z.infer<typeof rateTripSchema>
