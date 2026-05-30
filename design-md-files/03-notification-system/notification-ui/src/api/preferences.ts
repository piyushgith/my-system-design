import apiClient from './axios'
import type { UserNotificationPreference, UpdatePreferencesRequest } from '../types'

export async function getPreferences(
  userId: string
): Promise<UserNotificationPreference[]> {
  const { data } = await apiClient.get<UserNotificationPreference[]>(
    `/api/v1/users/${userId}/notification-preferences`
  )
  return data
}

export async function updatePreferences(
  userId: string,
  body: UpdatePreferencesRequest
): Promise<UserNotificationPreference[]> {
  const { data } = await apiClient.patch<UserNotificationPreference[]>(
    `/api/v1/users/${userId}/notification-preferences`,
    body
  )
  return data
}
