import { apiClient } from './axios'
import type { AccountResponse, BalanceResponse, OpenAccountRequest } from '../types'

export const accountApi = {
  open: (data: OpenAccountRequest, idempotencyKey: string) =>
    apiClient
      .post<AccountResponse>('/accounts', data, {
        headers: { 'Idempotency-Key': idempotencyKey },
      })
      .then((r) => r.data),

  get: (accountId: string) =>
    apiClient.get<AccountResponse>(`/accounts/${accountId}`).then((r) => r.data),

  getBalance: (accountId: string) =>
    apiClient.get<BalanceResponse>(`/accounts/${accountId}/balance`).then((r) => r.data),
}
