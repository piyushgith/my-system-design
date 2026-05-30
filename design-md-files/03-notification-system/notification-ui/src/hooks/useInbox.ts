import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getInbox, markAsRead, markAllAsRead } from '../api/inbox'

interface InboxParams {
  unreadOnly?: boolean
  page?: number
  size?: number
}

export function useInbox(userId: string | undefined, params: InboxParams = {}) {
  return useQuery({
    queryKey: ['inbox', userId, params],
    queryFn: () => getInbox(userId!, params),
    enabled: !!userId,
    staleTime: 10_000,
  })
}

export function useMarkAsRead(userId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (inboxItemId: string) => markAsRead(userId, inboxItemId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['inbox', userId] })
    },
  })
}

export function useMarkAllAsRead(userId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => markAllAsRead(userId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['inbox', userId] })
    },
  })
}
