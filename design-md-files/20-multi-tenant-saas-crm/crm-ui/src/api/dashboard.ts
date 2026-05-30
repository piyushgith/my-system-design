import { apiClient } from './client'
import type { DashboardResponse } from '@/types'

export const dashboardApi = {
  getSummary: () =>
    apiClient.get<DashboardResponse>('/v1/dashboard').then((r) => r.data),
}
