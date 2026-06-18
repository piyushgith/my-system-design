import { apiClient } from '@/api/client'
import { createIdempotencyKey } from '@/utils/idempotency'
import type {
  CompleteTripPayload,
  GoOnlinePayload,
  GoOnlineResponse,
  GoOfflineResponse,
  DriverAvailabilityResponse,
  LocationUpdatePayload,
  RejectTripPayload,
  StartTripPayload,
} from '@/types/api/driver.types'
import type {
  ArriveTripResponse,
  CompleteTripResponse,
  DriverAcceptResponse,
  DriverOfferResponse,
  StartTripResponse,
  TripSummary,
} from '@/types/api/trip.types'

export const driverService = {
  goOnline: async (payload: GoOnlinePayload): Promise<GoOnlineResponse> => {
    const { data } = await apiClient.post<GoOnlineResponse>('/v1/driver/availability/online', {
      vehicleId: payload.vehicleId,
      cityId: payload.cityId,
      lat: payload.lat,
      lng: payload.lng,
    })
    return data
  },

  goOffline: async (): Promise<GoOfflineResponse> => {
    const { data } = await apiClient.post<GoOfflineResponse>('/v1/driver/availability/offline')
    return data
  },

  updateLocation: async (payload: LocationUpdatePayload): Promise<void> => {
    await apiClient.post('/v1/driver/location', {
      lat: payload.lat,
      lng: payload.lng,
      heading: payload.heading,
      speedKmh: payload.speedKmh,
    })
  },

  getOffer: async (): Promise<DriverOfferResponse> => {
    const { data } = await apiClient.get<DriverOfferResponse>('/v1/driver/trips/offer', {
      headers: { 'X-Silent-Error': 'true' },
    })
    return data
  },

  getAvailability: async (): Promise<DriverAvailabilityResponse> => {
    const { data } = await apiClient.get<DriverAvailabilityResponse>('/v1/driver/availability')
    return data
  },

  getPending: async (): Promise<TripSummary[]> => {
    const { data } = await apiClient.get<TripSummary[]>('/v1/driver/trips/pending')
    return data
  },

  acceptTrip: async (tripId: string, idempotencyKey = createIdempotencyKey()): Promise<DriverAcceptResponse> => {
    const { data } = await apiClient.post<DriverAcceptResponse>(
      `/v1/driver/trips/${tripId}/accept`,
      {},
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )
    return data
  },

  rejectTrip: async (tripId: string, payload?: RejectTripPayload): Promise<void> => {
    await apiClient.post(`/v1/driver/trips/${tripId}/reject`, payload ?? {})
  },

  arrive: async (tripId: string, idempotencyKey = createIdempotencyKey()): Promise<ArriveTripResponse> => {
    const { data } = await apiClient.post<ArriveTripResponse>(
      `/v1/driver/trips/${tripId}/arrive`,
      {},
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )
    return data
  },

  startTrip: async (
    tripId: string,
    payload: StartTripPayload,
    idempotencyKey = createIdempotencyKey(),
  ): Promise<StartTripResponse> => {
    const { data } = await apiClient.post<StartTripResponse>(
      `/v1/driver/trips/${tripId}/start`,
      { otp: payload.otp },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )
    return data
  },

  completeTrip: async (
    tripId: string,
    payload: CompleteTripPayload,
    idempotencyKey = createIdempotencyKey(),
  ): Promise<CompleteTripResponse> => {
    const { data } = await apiClient.post<CompleteTripResponse>(
      `/v1/driver/trips/${tripId}/complete`,
      { finalLat: payload.finalLat, finalLng: payload.finalLng },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )
    return data
  },
}
