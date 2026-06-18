import { apiClient } from '@/api/client'
import { getFile } from '@/api/files'
import type {
  FileMetadataResponse,
  PresignedConfirmRequest,
  PresignedUploadInitResponse,
  UploadInitRequest,
  UploadPartResponse,
  UploadSessionResponse,
} from '@/types/api'
import { sleep } from '@/utils/presigned'

export async function initUpload(request: UploadInitRequest): Promise<UploadSessionResponse> {
  const { data } = await apiClient.post<UploadSessionResponse>('/api/v1/uploads/init', request)
  return data
}

export async function presignedInit(request: UploadInitRequest): Promise<PresignedUploadInitResponse> {
  const { data } = await apiClient.post<PresignedUploadInitResponse>(
    '/api/v1/uploads/presigned-init',
    request,
  )
  return data
}

export async function presignedConfirm(
  sessionId: string,
  request: PresignedConfirmRequest,
): Promise<void> {
  await apiClient.post(`/api/v1/uploads/${sessionId}/presigned-confirm`, request)
}

export async function uploadPart(
  sessionId: string,
  partNumber: number,
  chunk: Blob,
): Promise<UploadPartResponse> {
  const { data } = await apiClient.put<UploadPartResponse>(
    `/api/v1/uploads/${sessionId}/parts/${partNumber}`,
    chunk,
    {
      headers: {
        'Content-Type': 'application/octet-stream',
      },
    },
  )
  return data
}

export async function completeUpload(sessionId: string): Promise<FileMetadataResponse> {
  const { data } = await apiClient.post<FileMetadataResponse>(`/api/v1/uploads/${sessionId}/complete`)
  return data
}

async function getUploadStatus(sessionId: string): Promise<UploadSessionResponse> {
  const { data } = await apiClient.get<UploadSessionResponse>(`/api/v1/uploads/${sessionId}`)
  return data
}

export async function pollUploadUntilComplete(
  sessionId: string,
  options?: { intervalMs?: number; maxAttempts?: number },
): Promise<FileMetadataResponse> {
  const intervalMs = options?.intervalMs ?? 1000
  const maxAttempts = options?.maxAttempts ?? 300

  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    const session = await getUploadStatus(sessionId)

    if (session.status === 'COMPLETED') {
      if (!session.fileId) {
        throw new Error('Upload completed but fileId is missing')
      }
      return getFile(session.fileId)
    }

    if (session.status === 'ABORTED') {
      throw new Error('Upload finalization was aborted')
    }

    await sleep(intervalMs)
  }

  throw new Error('Upload finalization timed out')
}
