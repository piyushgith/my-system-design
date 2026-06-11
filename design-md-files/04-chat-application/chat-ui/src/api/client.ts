import axios, { AxiosError } from 'axios';
import type { ApiError } from '../types';

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';

export const apiClient = axios.create({
  baseURL: BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 10_000,
});

apiClient.interceptors.request.use((config) => {
  const raw = localStorage.getItem('chat:auth');
  if (raw) {
    try {
      const parsed = JSON.parse(raw);
      if (parsed?.token) {
        config.headers.Authorization = `Bearer ${parsed.token}`;
      }
    } catch {
      // ignore corrupt storage
    }
  }
  return config;
});

apiClient.interceptors.response.use(
  (res) => res,
  (error: AxiosError<ApiError>) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('chat:auth');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  },
);

export function extractApiError(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as ApiError | undefined;
    if (data?.errors?.length) {
      return data.errors.map((e) => `${e.field}: ${e.message}`).join(', ');
    }
    return data?.message ?? error.message ?? 'Request failed';
  }
  return 'An unexpected error occurred';
}
