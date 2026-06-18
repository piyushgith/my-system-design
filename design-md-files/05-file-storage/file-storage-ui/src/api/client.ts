import axios, { type AxiosError } from 'axios'
import type { ProblemDetail } from '@/types/api'
import { useSettingsStore } from '@/store/settingsStore'

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

export const apiClient = axios.create({
  baseURL: BASE_URL,
  timeout: 120_000,
})

apiClient.interceptors.request.use((config) => {
  const ownerId = useSettingsStore.getState().ownerId
  if (ownerId) {
    config.headers['X-Owner-Id'] = ownerId
  }
  return config
})

export function extractApiError(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as ProblemDetail | undefined
    if (data?.detail) return data.detail
    if (data?.title) return data.title
    return error.message
  }
  if (error instanceof Error) return error.message
  return 'An unexpected error occurred'
}

function isAxiosError(error: unknown): error is AxiosError {
  return axios.isAxiosError(error)
}

export function isNotImplemented(error: unknown): boolean {
  return isAxiosError(error) && error.response?.status === 501
}
