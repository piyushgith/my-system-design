import type { TripStatus } from './common.types'
import type { PaginationMeta } from './common.types'

export type TripLinks = {
  self: string
  cancel: string
  stream: string
}

export type EstimatedFare = {
  min: number
  max: number
}

export type TripVehicle = {
  make: string
  model: string
  color: string
  plate: string
}

export type TripDriver = {
  driver_id: string
  name: string
  rating: number
  vehicle: TripVehicle
}

export type DriverLocation = {
  lat: number
  lng: number
  heading: number | null
  updated_at: string
}

export type TripResponse = {
  trip_id: string
  status: TripStatus
  estimated_fare: EstimatedFare
  surge_multiplier: number
  pickup_address: string
  pickup_lat?: number
  pickup_lng?: number
  destination_address: string
  destination_lat?: number
  destination_lng?: number
  rated_by_rider?: boolean
  _links: TripLinks
  otp?: string | null
  eta_minutes?: number
  started_at?: string | null
  ended_at?: string | null
  driver?: TripDriver
  driver_location?: DriverLocation
}

export type CreateTripPayload = {
  quoteId: string
  pickupAddress: string
  destinationAddress: string
}

export type CancelTripPayload = {
  reason?: string
}

export type CancelTripResponse = {
  trip_id: string
  status: TripStatus
  cancellation_fee: number
  reason: string
}

export type RateTripPayload = {
  score: number
  comment?: string
}

export type RateTripResponse = {
  rating_id: string
  score: number
  trip_id: string
}

export type TripSummary = {
  trip_id: string
  status: TripStatus
  pickup_address: string
  destination_address: string
  requested_at: string
  final_fare: number | null
  rated_by_rider?: boolean
}

export type ActiveTripResponse = {
  trip_id?: string
  status?: TripStatus
  trip?: null
}

export type TripHistoryResponse = {
  trips: TripSummary[]
  pagination: PaginationMeta
}

export type DriverAcceptResponse = {
  trip_id: string
  status: TripStatus
  rider: {
    name: string
    rating: number
    otp: string
  }
  pickup: {
    lat: number
    lng: number
    address: string
  }
  destination_address: string
  estimated_fare_share: number
  navigation_url: string
}

export type DriverOfferResponse = {
  offer_id?: string
  trip_id?: string
  expires_at?: string
  pickup_address?: string
  destination_address?: string
  estimated_fare_min?: number
  offer?: null
}

export type CompleteTripResponse = {
  trip_id: string
  status: TripStatus
  fare: {
    total: number
    driver_share: number
    platform_fee: number
  }
  distance_km: number
  duration_min: number
}

export type StartTripResponse = {
  trip_id: string
  status: TripStatus
  destination: {
    lat: number
    lng: number
    address: string
  }
  navigation_url: string
}

export type ArriveTripResponse = {
  trip_id: string
  status: TripStatus
  wait_time_started_at: string
}
