import apiClient from './axios'
import type {
  SubmitNotificationRequest,
  SubmitNotificationResponse,
  NotificationStatusResponse,
} from '../types'

export async function submitNotification(
  body: SubmitNotificationRequest,
  idempotencyKey: string
): Promise<SubmitNotificationResponse> {
  const { data } = await apiClient.post<SubmitNotificationResponse>(
    '/api/v1/notifications',
    body,
    { headers: { 'Idempotency-Key': idempotencyKey } }
  )
  return data
}

export async function getNotification(
  notificationId: string
): Promise<NotificationStatusResponse> {
  const { data } = await apiClient.get<NotificationStatusResponse>(
    `/api/v1/notifications/${notificationId}`
  )
  return data
}

export async function cancelNotification(
  notificationId: string
): Promise<{ status: string }> {
  const { data } = await apiClient.delete<{ status: string }>(
    `/api/v1/notifications/${notificationId}`
  )
  return data
}
