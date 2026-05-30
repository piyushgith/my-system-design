import apiClient from './axios'
import type { InAppInboxItem, Page } from '../types'

export async function getInbox(
  userId: string,
  params: { unreadOnly?: boolean; page?: number; size?: number }
): Promise<Page<InAppInboxItem>> {
  const { data } = await apiClient.get<Page<InAppInboxItem>>(
    `/api/v1/users/${userId}/inbox`,
    { params: { unreadOnly: params.unreadOnly ?? false, page: params.page ?? 0, size: params.size ?? 20 } }
  )
  return data
}

export async function markAsRead(
  userId: string,
  inboxItemId: string
): Promise<InAppInboxItem> {
  const { data } = await apiClient.patch<InAppInboxItem>(
    `/api/v1/users/${userId}/inbox/${inboxItemId}`
  )
  return data
}

export async function markAllAsRead(
  userId: string
): Promise<{ count: number }> {
  const { data } = await apiClient.post<{ count: number }>(
    `/api/v1/users/${userId}/inbox/read-all`
  )
  return data
}
