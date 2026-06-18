import { apiClient } from '@/api/client'
import type { FareEstimatePayload, FareEstimateResponse } from '@/types/api/pricing.types'

export const pricingService = {
  estimate: async (payload: FareEstimatePayload): Promise<FareEstimateResponse> => {
    const { data } = await apiClient.post<FareEstimateResponse>('/v1/fare-estimates', {
      pickupLat: payload.pickupLat,
      pickupLng: payload.pickupLng,
      destinationLat: payload.destinationLat,
      destinationLng: payload.destinationLng,
      vehicleType: payload.vehicleType,
      cityId: payload.cityId,
    })
    return data
  },
}
