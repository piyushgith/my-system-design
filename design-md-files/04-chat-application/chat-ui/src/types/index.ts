export type ConversationType = 'DIRECT' | 'GROUP' | 'CHANNEL';
export type MemberRole = 'OWNER' | 'ADMIN' | 'MEMBER';
export type MessageStatus = 'SENT' | 'DELIVERED' | 'READ';
export type PresenceStatus = 'ONLINE' | 'AWAY' | 'OFFLINE';

// Auth
export interface AuthResponse {
  userId: string;
  username: string;
  displayName: string;
  token: string;
}

export interface LoginRequest {
  login: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  displayName: string;
  email: string;
  password: string;
}

// Conversations
export interface MemberResponse {
  userId: string;
  role: MemberRole;
  joinedAt: string;
}

export interface ConversationResponse {
  conversationId: string;
  type: ConversationType;
  name: string | null;
  createdAt: string;
  members: MemberResponse[];
}

export interface ConversationSummary {
  conversationId: string;
  type: ConversationType;
  name: string | null;
  lastMessageAt: string | null;
  memberCount: number;
}

export interface ConversationListResponse {
  conversations: ConversationSummary[];
  hasMore: boolean;
}

export interface CreateConversationRequest {
  type: ConversationType;
  name?: string;
  memberIds: string[];
}

export interface AddMemberRequest {
  userId: string;
}

// Messages
export interface MessageResponse {
  messageId: string;
  sequenceNum: number;
  senderId: string;
  contentType: string;
  content: string;
  sentAt: string;
  serverReceivedAt: string;
  status: MessageStatus;
}

export interface MessageHistoryResponse {
  messages: MessageResponse[];
  hasMore: boolean;
  oldestSeq: number | null;
}

export interface SendMessageRequest {
  idempotencyKey?: string;
  contentType: string;
  content: string;
}

// Presence
export interface PresenceEntry {
  status: PresenceStatus;
  lastSeen: string | null;
}

export interface PresenceQueryResponse {
  presence: Record<string, PresenceEntry>;
}

// WebSocket frames
export interface WsFrame {
  type: string;
  frame_id?: string;
  payload?: Record<string, unknown>;
}

export interface WsNewMessagePayload {
  message_id: string;
  conversation_id: string;
  sender_id: string;
  sequence_num: number;
  content_type: string;
  content: string;
  sent_at: string;
}

export interface WsMessageAckPayload {
  message_id: string;
  sequence_num: number;
  status: MessageStatus;
  server_received_at: string;
}

// Error
export interface ApiError {
  code: string;
  message: string;
  requestId?: string;
  timestamp?: string;
  errors?: Array<{ field: string; message: string }>;
}

// Derived UI types
export interface AuthUser {
  userId: string;
  username: string;
  displayName: string;
  token: string;
}
