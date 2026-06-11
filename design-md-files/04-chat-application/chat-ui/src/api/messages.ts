import { apiClient } from './client';
import type { MessageHistoryResponse, MessageResponse, SendMessageRequest } from '../types';

export const messagesApi = {
  history: (conversationId: string, beforeSeq?: number, limit = 50) =>
    apiClient
      .get<MessageHistoryResponse>(`/api/v1/conversations/${conversationId}/messages`, {
        params: { ...(beforeSeq !== undefined ? { beforeSeq } : {}), limit },
      })
      .then((r) => r.data),

  send: (conversationId: string, data: SendMessageRequest) =>
    apiClient
      .post<MessageResponse>(`/api/v1/conversations/${conversationId}/messages`, data)
      .then((r) => r.data),
};
