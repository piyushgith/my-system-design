import { apiClient } from './client';
import type { PresenceQueryResponse } from '../types';

export const presenceApi = {
  query: (userIds: string[]) =>
    apiClient
      .post<PresenceQueryResponse>('/api/v1/presence/query', { userIds })
      .then((r) => r.data),
};
