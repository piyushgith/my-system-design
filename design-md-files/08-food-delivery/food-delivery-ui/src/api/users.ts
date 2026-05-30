import { apiClient } from './axios'
import type {
  UserProfileResponse,
  AddressResponse,
  AddAddressRequest,
  UpdateProfileRequest,
} from '../types/user'

export const usersApi = {
  getProfile: () =>
    apiClient.get<UserProfileResponse>('/users/me'),

  updateProfile: (data: UpdateProfileRequest) =>
    apiClient.patch<UserProfileResponse>('/users/me', data),

  listAddresses: () =>
    apiClient.get<AddressResponse[]>('/users/me/addresses'),

  addAddress: (data: AddAddressRequest) =>
    apiClient.post<AddressResponse>('/users/me/addresses', data),

  deleteAddress: (addressId: string) =>
    apiClient.delete<void>(`/users/me/addresses/${addressId}`),
}
