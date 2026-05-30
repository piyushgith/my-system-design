import type { ErrorResponse } from '../types'
import { AxiosError } from 'axios'

export function extractErrorMessage(err: unknown): string {
  if (err instanceof AxiosError) {
    const data = err.response?.data as ErrorResponse | undefined
    if (data?.error?.message) return data.error.message
    if (err.message) return err.message
  }
  if (err instanceof Error) return err.message
  return 'An unexpected error occurred'
}

export function extractErrorCode(err: unknown): string | null {
  if (err instanceof AxiosError) {
    const data = err.response?.data as ErrorResponse | undefined
    return data?.error?.code ?? null
  }
  return null
}
