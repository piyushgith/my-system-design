import { useCallback, useEffect, useRef, useState } from 'react'
import { uploadFile } from '@/api/files'
import { extractApiError, isNotImplemented } from '@/api/client'
import {
  completeUpload,
  initUpload,
  pollUploadUntilComplete,
  presignedConfirm,
  presignedInit,
  uploadPart,
} from '@/api/uploads'
import { useSettingsStore } from '@/store/settingsStore'
import { useToastStore } from '@/store/toastStore'
import type { FileMetadataResponse, UploadProgress } from '@/types/api'
import { shouldUseMultipart, splitFileIntoChunks } from '@/utils/format'
import { sha256Hex } from '@/utils/hash'
import { putToPresignedUrl } from '@/utils/presigned'

const initialProgress = (): UploadProgress => ({
  fileName: '',
  bytesUploaded: 0,
  totalBytes: 0,
  phase: 'init',
})

export function useUpload(onSuccess?: (file: FileMetadataResponse) => void) {
  const pushToast = useToastStore((s) => s.push)
  const [isUploading, setIsUploading] = useState(false)
  const [progress, setProgress] = useState<UploadProgress | null>(null)
  const clearTimerRef = useRef<number | null>(null)

  useEffect(
    () => () => {
      if (clearTimerRef.current !== null) window.clearTimeout(clearTimerRef.current)
    },
    [],
  )

  const uploadViaMultipart = useCallback(
    async (file: File, mimeType: string) => {
      const session = await initUpload({ fileName: file.name, mimeType })
      const chunks = splitFileIntoChunks(file)

      setProgress((p) => (p ? { ...p, phase: 'parts' } : p))

      let uploaded = 0
      await Promise.all(
        chunks.map(async (chunk, i) => {
          await uploadPart(session.uploadId, i + 1, chunk)
          uploaded += chunk.size
          setProgress((p) =>
            p
              ? {
                  ...p,
                  bytesUploaded: uploaded,
                  phase: 'parts',
                  message: 'via app server',
                }
              : p,
          )
        }),
      )

      setProgress((p) => (p ? { ...p, phase: 'complete' } : p))
      return completeUpload(session.uploadId)
    },
    [],
  )

  const uploadViaPresigned = useCallback(async (file: File, mimeType: string) => {
    const init = await presignedInit({ fileName: file.name, mimeType })

    setProgress((p) =>
      p ? { ...p, phase: 'hashing', message: 'computing SHA-256' } : p,
    )

    const contentHash = await sha256Hex(file, (loaded, total) => {
      setProgress((p) =>
        p
          ? {
              ...p,
              bytesUploaded: Math.floor(loaded * 0.15),
              totalBytes: total,
              phase: 'hashing',
            }
          : p,
      )
    })

    setProgress((p) =>
      p ? { ...p, phase: 'presigned', message: 'direct to object store' } : p,
    )

    await putToPresignedUrl(init.presignedUrl, file, mimeType, (loaded, total) => {
      setProgress((p) =>
        p
          ? {
              ...p,
              bytesUploaded: Math.floor(total * 0.15 + loaded * 0.75),
              totalBytes: total,
              phase: 'presigned',
            }
          : p,
      )
    })

    setProgress((p) =>
      p
        ? {
            ...p,
            bytesUploaded: Math.floor(file.size * 0.9),
            phase: 'processing',
            message: 'confirming upload',
          }
        : p,
    )

    await presignedConfirm(init.sessionId, { contentHash, sizeBytes: file.size })

    setProgress((p) =>
      p
        ? {
            ...p,
            phase: 'processing',
            message: 'awaiting finalization',
          }
        : p,
    )

    return pollUploadUntilComplete(init.sessionId)
  }, [])

  const upload = useCallback(
    async (file: File) => {
      const preferPresigned = useSettingsStore.getState().preferPresigned
      const mimeType = file.type || 'application/octet-stream'

      if (clearTimerRef.current !== null) {
        window.clearTimeout(clearTimerRef.current)
        clearTimerRef.current = null
      }

      setIsUploading(true)
      setProgress({
        fileName: file.name,
        bytesUploaded: 0,
        totalBytes: file.size,
        phase: 'init',
      })

      try {
        let result: FileMetadataResponse

        if (shouldUseMultipart(file.size)) {
          if (preferPresigned) {
            try {
              result = await uploadViaPresigned(file, mimeType)
            } catch (error) {
              if (isNotImplemented(error)) {
                pushToast('Presigned upload unavailable — using multipart via app server', 'info')
                result = await uploadViaMultipart(file, mimeType)
              } else {
                throw error
              }
            }
          } else {
            result = await uploadViaMultipart(file, mimeType)
          }
        } else {
          result = await uploadFile(file)
        }

        setProgress((p) =>
          p
            ? {
                ...p,
                phase: 'done',
                bytesUploaded: file.size,
                message: undefined,
              }
            : p,
        )
        pushToast(`"${file.name}" archived successfully`, 'success')
        onSuccess?.(result)
      } catch (error) {
        const message = extractApiError(error)
        setProgress((p) =>
          p
            ? { ...p, phase: 'error', message }
            : { ...initialProgress(), fileName: file.name, totalBytes: file.size, phase: 'error', message },
        )
        pushToast(message, 'error')
      } finally {
        setIsUploading(false)
        clearTimerRef.current = window.setTimeout(() => {
          setProgress(null)
          clearTimerRef.current = null
        }, 2400)
      }
    },
    [onSuccess, pushToast, uploadViaMultipart, uploadViaPresigned],
  )

  return { upload, isUploading, progress }
}
