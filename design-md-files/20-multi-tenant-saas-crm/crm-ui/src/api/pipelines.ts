import { apiClient } from './client'
import type { Pipeline, Stage } from '@/types'

export const pipelinesApi = {
  list: () =>
    apiClient.get<Pipeline[]>('/v1/pipelines').then((r) => r.data),

  listStages: (pipelineId: string) =>
    apiClient
      .get<Stage[]>(`/v1/pipelines/${pipelineId}/stages`)
      .then((r) => r.data),
}
