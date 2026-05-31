import { useMutation } from '@tanstack/react-query'
import { createShortUrl } from '@/api/urls'
import { useHistoryStore } from '@/store/historyStore'
import type { CreateUrlRequest, CreateUrlResponse, ApiError } from '@/types/api'

export function useCreateUrl() {
  const addToHistory = useHistoryStore((s) => s.add)

  return useMutation<CreateUrlResponse, ApiError, CreateUrlRequest>({
    mutationFn: createShortUrl,
    onSuccess: (data) => {
      addToHistory({ ...data, savedAt: new Date().toISOString() })
    },
  })
}
