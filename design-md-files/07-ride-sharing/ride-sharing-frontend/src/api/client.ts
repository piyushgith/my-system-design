import axios from 'axios'
import { API_BASE_URL } from '@/constants/demo'
import { useAuthStore } from '@/store/auth.store'
import { parseApiError } from '@/api/errors'
import { useUiStore } from '@/store/ui.store'

const SILENT_TOAST_CODES = new Set(['TRIP_ALREADY_ACTIVE'])

const isSilentRequest = (error: unknown): boolean => {
  if (!axios.isAxiosError(error) || !error.config?.headers) return false
  const headers = error.config.headers
  if (typeof headers.get === 'function') {
    return Boolean(headers.get('X-Silent-Error'))
  }
  return Boolean((headers as Record<string, unknown>)['X-Silent-Error'])
}

export const apiClient = axios.create({
  baseURL: API_BASE_URL || undefined,
  headers: {
    'Content-Type': 'application/json',
  },
})

apiClient.interceptors.request.use((config) => {
  const auth = useAuthStore.getState()
  if (auth.isAuthenticated()) {
    const headers = auth.getAuthHeaders()
    config.headers.set('X-Uid', headers['X-Uid'])
    if (headers['X-Role']) {
      config.headers.set('X-Role', headers['X-Role'])
    }
  }
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const apiError = parseApiError(error)
    const silent = isSilentRequest(error) || SILENT_TOAST_CODES.has(apiError.code)
    if (apiError.status === 401) {
      useAuthStore.getState().logout()
    }
    if (!silent) {
      useUiStore.getState().addToast({
        type: 'error',
        message: apiError.message,
      })
    }
    return Promise.reject(apiError)
  },
)
