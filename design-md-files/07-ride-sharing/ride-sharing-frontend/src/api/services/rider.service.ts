import { apiClient } from '@/api/client'
import type {
  RiderProfileResponse,
  UpdateRiderPayload,
  UpdateRiderResponse,
} from '@/types/api/rider.types'

export const riderService = {
  getProfile: async (): Promise<RiderProfileResponse> => {
    const { data } = await apiClient.get<RiderProfileResponse>('/v1/riders/me')
    return data
  },

  updateProfile: async (payload: UpdateRiderPayload): Promise<UpdateRiderResponse> => {
    const { data } = await apiClient.patch<UpdateRiderResponse>('/v1/riders/me', {
      fullName: payload.fullName,
      email: payload.email,
    })
    return data
  },
}
