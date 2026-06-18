import { useCallback, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { deleteFile, listFiles } from '@/api/files'
import { extractApiError } from '@/api/client'
import { useToastStore } from '@/store/toastStore'

function useFileList(page = 0, size = 24) {
  const queryClient = useQueryClient()
  const pushToast = useToastStore((s) => s.push)

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['files', page, size],
    queryFn: () => listFiles(page, size),
  })

  const deleteMutation = useMutation({
    mutationFn: deleteFile,
    onSuccess: () => {
      pushToast('File moved to deletion queue', 'success')
      queryClient.invalidateQueries({ queryKey: ['files'] })
    },
    onError: (error) => pushToast(extractApiError(error), 'error'),
  })

  const handleDelete = useCallback(
    (fileId: string) => {
      deleteMutation.mutate(fileId)
    },
    [deleteMutation],
  )

  return {
    data,
    isLoading,
    isError,
    error,
    refetch,
    handleDelete,
    isDeleting: deleteMutation.isPending,
  }
}

export function usePagination(defaultSize = 24) {
  const [page, setPage] = useState(0)
  const { data, isLoading, isError, error, refetch, handleDelete, isDeleting } = useFileList(page, defaultSize)

  const totalPages = data?.totalPages ?? 0

  // Deleting the last item on a non-first page would strand the user on an empty page,
  // so step back before the list refetches.
  const handleDeleteWithPaging = useCallback(
    (fileId: string) => {
      if (data && data.items.length === 1 && page > 0) {
        setPage((prev) => prev - 1)
      }
      handleDelete(fileId)
    },
    [data, page, handleDelete],
  )

  return {
    page,
    setPage,
    data,
    isLoading,
    isError,
    error,
    refetch,
    handleDelete: handleDeleteWithPaging,
    isDeleting,
    totalPages,
    canPrev: page > 0,
    canNext: page + 1 < totalPages,
  }
}
