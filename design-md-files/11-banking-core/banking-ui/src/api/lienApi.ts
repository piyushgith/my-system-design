import { apiClient } from './axios'
import type { LienResponse, PlaceLienRequest } from '../types'

export const lienApi = {
  list: (accountId: string) =>
    apiClient.get<LienResponse[]>(`/accounts/${accountId}/liens`).then((r) => r.data),

  place: (accountId: string, data: PlaceLienRequest) =>
    apiClient.post<LienResponse>(`/accounts/${accountId}/liens`, data).then((r) => r.data),

  release: (accountId: string, lienId: string) =>
    apiClient
      .post<LienResponse>(`/accounts/${accountId}/liens/${lienId}/release`)
      .then((r) => r.data),
}
