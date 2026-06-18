import { apiClient } from '@/api/client'
import { createIdempotencyKey } from '@/utils/idempotency'
import type {
  CancelTripPayload,
  CancelTripResponse,
  CreateTripPayload,
  RateTripPayload,
  RateTripResponse,
  TripHistoryResponse,
  TripResponse,
  ActiveTripResponse,
} from '@/types/api/trip.types'

export const tripService = {
  create: async (payload: CreateTripPayload, idempotencyKey = createIdempotencyKey()): Promise<TripResponse> => {
    const { data } = await apiClient.post<TripResponse>(
      '/v1/trips',
      {
        quoteId: payload.quoteId,
        pickupAddress: payload.pickupAddress,
        destinationAddress: payload.destinationAddress,
      },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )
    return data
  },

  get: async (tripId: string): Promise<TripResponse> => {
    const { data } = await apiClient.get<TripResponse>(`/v1/trips/${tripId}`)
    return data
  },

  cancel: async (
    tripId: string,
    payload?: CancelTripPayload,
    idempotencyKey = createIdempotencyKey(),
  ): Promise<CancelTripResponse> => {
    const { data } = await apiClient.post<CancelTripResponse>(
      `/v1/trips/${tripId}/cancel`,
      payload ?? {},
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )
    return data
  },

  rate: async (tripId: string, payload: RateTripPayload): Promise<RateTripResponse> => {
    const { data } = await apiClient.post<RateTripResponse>(`/v1/trips/${tripId}/ratings`, {
      score: payload.score,
      comment: payload.comment,
    })
    return data
  },

  history: async (): Promise<TripHistoryResponse> => {
    const { data } = await apiClient.get<TripHistoryResponse>('/v1/riders/me/trips')
    return data
  },

  getActive: async (): Promise<ActiveTripResponse> => {
    const { data } = await apiClient.get<ActiveTripResponse>('/v1/riders/me/trips/active')
    return data
  },
}
