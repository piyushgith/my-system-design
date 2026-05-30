import { apiClient } from './client'
import type { ContactRequest, ContactResponse, LeadStatus, PageResponse } from '@/types'

export interface ContactListParams {
  leadStatus?: LeadStatus
  ownerId?: string
  page?: number
  pageSize?: number
}

export const contactsApi = {
  list: (params: ContactListParams = {}) =>
    apiClient
      .get<PageResponse<ContactResponse>>('/v1/contacts', { params })
      .then((r) => r.data),

  get: (id: string) =>
    apiClient.get<ContactResponse>(`/v1/contacts/${id}`).then((r) => r.data),

  create: (req: ContactRequest) =>
    apiClient.post<ContactResponse>('/v1/contacts', req).then((r) => r.data),

  update: (id: string, req: ContactRequest) =>
    apiClient.put<ContactResponse>(`/v1/contacts/${id}`, req).then((r) => r.data),

  delete: (id: string) =>
    apiClient.delete(`/v1/contacts/${id}`).then((r) => r.data),
}
