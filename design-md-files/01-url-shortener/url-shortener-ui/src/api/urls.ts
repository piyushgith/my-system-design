import apiClient from './client'
import type { CreateUrlRequest, CreateUrlResponse } from '@/types/api'

export async function createShortUrl(request: CreateUrlRequest): Promise<CreateUrlResponse> {
  const payload: CreateUrlRequest = {
    longUrl: request.longUrl,
    ...(request.alias ? { alias: request.alias } : {}),
    ...(request.ttl ? { ttl: request.ttl } : {}),
  }
  const { data } = await apiClient.post<CreateUrlResponse>('/v1/urls', payload)
  return data
}
