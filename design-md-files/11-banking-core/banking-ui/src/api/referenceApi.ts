import { apiClient } from './axios'
import type { IfscValidationResponse } from '../types'

export const referenceApi = {
  validateIfsc: (code: string) =>
    apiClient.get<IfscValidationResponse>(`/reference/ifsc/${code}/validate`).then((r) => r.data),
}
