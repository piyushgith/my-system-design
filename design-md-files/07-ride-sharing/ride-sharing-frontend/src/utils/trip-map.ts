import { DEMO_DESTINATION, DEMO_PICKUP } from '@/constants/demo'
import type { TripResponse } from '@/types/api/trip.types'

type MapPoint = { lat: number; lng: number; address?: string }

export const getTripMapPoints = (trip: TripResponse): { pickup: MapPoint; destination: MapPoint } => ({
  pickup:
    trip.pickup_lat != null && trip.pickup_lng != null
      ? { lat: trip.pickup_lat, lng: trip.pickup_lng, address: trip.pickup_address }
      : DEMO_PICKUP,
  destination:
    trip.destination_lat != null && trip.destination_lng != null
      ? { lat: trip.destination_lat, lng: trip.destination_lng, address: trip.destination_address }
      : DEMO_DESTINATION,
})
