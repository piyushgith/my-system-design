import axios, { AxiosError } from 'axios'
import type { ApiError } from '@/types/api'

const apiClient = axios.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 10_000,
})

// Normalize Axios errors into ApiError shape for uniform handling
apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiError>) => {
    if (error.response?.data?.error) {
      return Promise.reject(error.response.data)
    }
    return Promise.reject({
      error: {
        code: 'NETWORK_ERROR',
        message: error.message ?? 'An unexpected error occurred',
        field: null,
        timestamp: new Date().toISOString(),
      },
    } satisfies ApiError)
  }
)

export default apiClient
