import { apiClient } from '@/api/client'
import type { FileMetadataResponse, PageResponse } from '@/types/api'

export async function listFiles(page = 0, size = 24): Promise<PageResponse<FileMetadataResponse>> {
  const { data } = await apiClient.get<PageResponse<FileMetadataResponse>>('/api/v1/files', {
    params: { page, size, sort: 'createdAt,desc' },
  })
  return data
}

export async function getFile(fileId: string): Promise<FileMetadataResponse> {
  const { data } = await apiClient.get<FileMetadataResponse>(`/api/v1/files/${fileId}`)
  return data
}

export async function uploadFile(file: File): Promise<FileMetadataResponse> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await apiClient.post<FileMetadataResponse>('/api/v1/files', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return data
}

export async function deleteFile(fileId: string): Promise<void> {
  await apiClient.delete(`/api/v1/files/${fileId}`)
}

export function getDownloadUrl(fileId: string): string {
  const base = import.meta.env.VITE_API_BASE_URL ?? ''
  return `${base}/api/v1/files/${fileId}/download`
}
