export type GoOnlinePayload = {
  vehicleId: string
  cityId: string
  lat: number
  lng: number
}

export type GoOnlineResponse = {
  status: string
  city_id: string
  timestamp: string
}

export type GoOfflineResponse = {
  status: string
  timestamp: string
}

export type DriverAvailabilityResponse = {
  status: 'OFFLINE' | 'AVAILABLE' | 'ON_TRIP'
  city_id?: string
  current_trip_id?: string
}

export type LocationUpdatePayload = {
  lat: number
  lng: number
  heading?: number
  speedKmh?: number
}

export type RejectTripPayload = {
  reason?: string
}

export type StartTripPayload = {
  otp: string
}

export type CompleteTripPayload = {
  finalLat: number
  finalLng: number
}
