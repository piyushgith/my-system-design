import { apiClient } from './client';
import type {
  AddMemberRequest,
  ConversationListResponse,
  ConversationResponse,
  CreateConversationRequest,
  MemberResponse,
} from '../types';

export const conversationsApi = {
  list: (limit = 50) =>
    apiClient
      .get<ConversationListResponse>('/api/v1/conversations', { params: { limit } })
      .then((r) => r.data),

  get: (conversationId: string) =>
    apiClient
      .get<ConversationResponse>(`/api/v1/conversations/${conversationId}`)
      .then((r) => r.data),

  create: (data: CreateConversationRequest) =>
    apiClient
      .post<ConversationResponse>('/api/v1/conversations', data)
      .then((r) => r.data),

  members: (conversationId: string) =>
    apiClient
      .get<MemberResponse[]>(`/api/v1/conversations/${conversationId}/members`)
      .then((r) => r.data),

  addMember: (conversationId: string, data: AddMemberRequest) =>
    apiClient
      .post<MemberResponse>(`/api/v1/conversations/${conversationId}/members`, data)
      .then((r) => r.data),
};
