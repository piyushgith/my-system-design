export interface FileMetadataResponse {
  fileId: string
  name: string
  mimeType: string
  sizeBytes: number
  contentHash: string
  backend: string
  ownerId: string | null
  createdAt: string
  downloadUrl: string
}

export interface PageResponse<T> {
  items: T[]
  page: number
  pageSize: number
  totalItems: number
  totalPages: number
}

export interface UploadSessionResponse {
  uploadId: string
  fileName: string
  status: UploadStatus
  receivedParts: number
  uploadedBytes: number
  fileId: string | null
  expiresAt: string
}

export type UploadStatus = 'IN_PROGRESS' | 'PROCESSING' | 'COMPLETED' | 'ABORTED'

export interface UploadInitRequest {
  fileName: string
  mimeType: string
}

export interface UploadPartResponse {
  partNumber: number
  etag: string
  sizeBytes: number
}

export interface PresignedUrlResponse {
  url: string
  expiresInSeconds: number
}

export interface PresignedUploadInitResponse {
  sessionId: string
  presignedUrl: string
  expiresAt: string
}

export interface PresignedConfirmRequest {
  contentHash: string
  sizeBytes: number
}

export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
}

export interface UploadProgress {
  fileName: string
  bytesUploaded: number
  totalBytes: number
  phase: 'init' | 'hashing' | 'presigned' | 'parts' | 'complete' | 'processing' | 'done' | 'error'
  message?: string
}
