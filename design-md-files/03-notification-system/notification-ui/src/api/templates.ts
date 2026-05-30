import apiClient from './axios'
import type { Template, CreateTemplateRequest } from '../types'

export async function createTemplate(body: CreateTemplateRequest): Promise<Template> {
  const { data } = await apiClient.post<Template>('/api/v1/templates', body)
  return data
}

export async function getTemplate(templateId: string): Promise<Template> {
  const { data } = await apiClient.get<Template>(`/api/v1/templates/${templateId}`)
  return data
}

export async function deprecateTemplate(
  templateId: string,
  version: number
): Promise<void> {
  await apiClient.delete(`/api/v1/templates/${templateId}/versions/${version}`)
}
