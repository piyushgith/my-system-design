import { apiClient } from './client'
import type {
  DealRequest,
  DealResponse,
  DealStatus,
  PageResponse,
  StageTransitionRequest,
} from '@/types'

export interface DealListParams {
  status?: DealStatus
  ownerId?: string
  pipelineId?: string
  page?: number
  pageSize?: number
}

export const dealsApi = {
  list: (params: DealListParams = {}) =>
    apiClient
      .get<PageResponse<DealResponse>>('/v1/deals', { params })
      .then((r) => r.data),

  get: (id: string) =>
    apiClient.get<DealResponse>(`/v1/deals/${id}`).then((r) => r.data),

  create: (req: DealRequest) =>
    apiClient.post<DealResponse>('/v1/deals', req).then((r) => r.data),

  update: (id: string, req: DealRequest) =>
    apiClient.put<DealResponse>(`/v1/deals/${id}`, req).then((r) => r.data),

  moveStage: (id: string, req: StageTransitionRequest) =>
    apiClient
      .post<DealResponse>(`/v1/deals/${id}/stage-transition`, req)
      .then((r) => r.data),

  delete: (id: string) =>
    apiClient.delete(`/v1/deals/${id}`).then((r) => r.data),
}
