import type { VehicleType } from './common.types'

export type FareEstimatePayload = {
  pickupLat: number
  pickupLng: number
  destinationLat: number
  destinationLng: number
  vehicleType: VehicleType
  cityId: string
}

export type FareBreakdown = {
  base_fare: number
  distance_fare: number
  time_fare: number
  surge_premium: number
  platform_fee: number
}

export type FareEstimateResponse = {
  quote_id: string
  vehicle_type: VehicleType
  fare_min: number
  fare_max: number
  currency: string
  surge_multiplier: number
  surge_active: boolean
  estimated_duration_min: number
  estimated_distance_km: number
  expires_at: string
  breakdown: FareBreakdown
}
