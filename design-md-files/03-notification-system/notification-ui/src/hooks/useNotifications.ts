import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  getNotification,
  submitNotification,
  cancelNotification,
} from '../api/notifications'
import type { SubmitNotificationRequest } from '../types'

export function useNotification(notificationId: string | undefined) {
  return useQuery({
    queryKey: ['notification', notificationId],
    queryFn: () => getNotification(notificationId!),
    enabled: !!notificationId,
    refetchInterval: (query) => {
      const status = query.state.data?.status
      if (!status) return false
      const terminal = ['DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED', 'EXPIRED']
      return terminal.includes(status) ? false : 3000
    },
  })
}

export function useSubmitNotification() {
  return useMutation({
    mutationFn: ({
      body,
      idempotencyKey,
    }: {
      body: SubmitNotificationRequest
      idempotencyKey: string
    }) => submitNotification(body, idempotencyKey),
  })
}

export function useCancelNotification(notificationId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => cancelNotification(notificationId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['notification', notificationId] })
    },
  })
}
