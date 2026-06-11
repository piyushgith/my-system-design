import { useEffect, useRef, useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { v4 as uuidv4 } from 'uuid';
import { useAuthStore } from '../store/authStore';
import type { MessageResponse, WsFrame, WsNewMessagePayload } from '../types';

const WS_BASE = import.meta.env.VITE_WS_BASE_URL ?? '';
const PING_INTERVAL_MS = 25_000;
const RECONNECT_DELAY_MS = 3_000;
const MAX_RECONNECT_ATTEMPTS = 10;

function appendIfNew(msg: MessageResponse) {
  return (prev: MessageResponse[] = []) =>
    prev.some((m) => m.messageId === msg.messageId) ? prev : [...prev, msg];
}

export function useWebSocket() {
  const token = useAuthStore((s) => s.user?.token);
  const queryClient = useQueryClient();
  const wsRef = useRef<WebSocket | null>(null);
  const pingTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectAttemptsRef = useRef(0);
  const mountedRef = useRef(true);

  // Stable ref so connect() always calls the latest handler without being in connect's deps.
  const onNewMessageRef = useRef<(payload: WsNewMessagePayload) => void>(() => undefined);

  useEffect(() => {
    onNewMessageRef.current = (payload: WsNewMessagePayload) => {
      const msg: MessageResponse = {
        messageId: payload.message_id,
        sequenceNum: payload.sequence_num,
        senderId: payload.sender_id,
        contentType: payload.content_type,
        content: payload.content,
        sentAt: payload.sent_at,
        serverReceivedAt: payload.sent_at,
        status: 'DELIVERED',
      };

      queryClient.setQueryData<MessageResponse[]>(
        ['messages', payload.conversation_id],
        appendIfNew(msg),
      );

      queryClient.invalidateQueries({ queryKey: ['conversations'] });
    };
  }, [queryClient]);

  const connect = useCallback(() => {
    if (!token || !mountedRef.current) return;
    if (wsRef.current?.readyState === WebSocket.OPEN) return;

    const url = `${WS_BASE}/ws?token=${encodeURIComponent(token)}`;
    const ws = new WebSocket(url);
    wsRef.current = ws;

    ws.onopen = () => {
      reconnectAttemptsRef.current = 0;
      pingTimerRef.current = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'PING', frame_id: uuidv4() }));
        }
      }, PING_INTERVAL_MS);
    };

    ws.onmessage = (event) => {
      try {
        const frame = JSON.parse(event.data as string) as WsFrame;
        if (frame.type === 'NEW_MESSAGE') {
          onNewMessageRef.current(frame.payload as unknown as WsNewMessagePayload);
        }
      } catch {
        // ignore malformed frames
      }
    };

    ws.onclose = () => {
      if (pingTimerRef.current) clearInterval(pingTimerRef.current);
      if (!mountedRef.current) return;
      if (reconnectAttemptsRef.current < MAX_RECONNECT_ATTEMPTS) {
        reconnectAttemptsRef.current++;
        reconnectTimerRef.current = setTimeout(connect, RECONNECT_DELAY_MS);
      }
    };

    ws.onerror = () => ws.close();
  }, [token]);

  const sendFrame = useCallback((frame: WsFrame) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify(frame));
      return true;
    }
    return false;
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    connect();
    return () => {
      mountedRef.current = false;
      if (pingTimerRef.current) clearInterval(pingTimerRef.current);
      if (reconnectTimerRef.current) clearTimeout(reconnectTimerRef.current);
      wsRef.current?.close();
    };
  }, [connect]);

  return { sendFrame, wsRef };
}
