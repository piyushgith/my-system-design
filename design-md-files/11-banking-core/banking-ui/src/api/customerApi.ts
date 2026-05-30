import { apiClient } from './axios'
import type { CreateCustomerRequest, CustomerResponse } from '../types'

export const customerApi = {
  create: (data: CreateCustomerRequest) =>
    apiClient.post<CustomerResponse>('/customers', data).then((r) => r.data),

  get: (cifId: string) =>
    apiClient.get<CustomerResponse>(`/customers/${cifId}`).then((r) => r.data),

  exists: (cifId: string) =>
    apiClient
      .get<CustomerResponse>(`/customers/${cifId}`)
      .then(() => true)
      .catch(() => false),
}
