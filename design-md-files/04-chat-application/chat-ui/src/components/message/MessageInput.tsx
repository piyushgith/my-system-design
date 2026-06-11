import { useState, useRef, useCallback } from 'react';
import { v4 as uuidv4 } from 'uuid';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { messagesApi } from '../../api/messages';
import { useAuthStore } from '../../store/authStore';
import { useToastStore } from '../../store/toastStore';
import { extractApiError } from '../../api/client';
import type { MessageResponse, WsFrame } from '../../types';

interface Props {
  readonly conversationId: string;
  readonly sendFrame: (frame: WsFrame) => boolean;
}

export function MessageInput({ conversationId, sendFrame }: Props) {
  const [value, setValue] = useState('');
  const userId = useAuthStore((s) => s.user?.userId);
  const push = useToastStore((s) => s.push);
  const queryClient = useQueryClient();
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const restMutation = useMutation({
    mutationFn: (content: string) =>
      messagesApi.send(conversationId, {
        idempotencyKey: uuidv4(),
        contentType: 'TEXT',
        content,
      }),
    onSuccess: (msg) => {
      queryClient.setQueryData<MessageResponse[]>(
        ['messages', conversationId],
        (prev = []) => {
          if (prev.some((m) => m.messageId === msg.messageId)) return prev;
          return [...prev, msg];
        },
      );
      queryClient.invalidateQueries({ queryKey: ['conversations'] });
    },
    onError: (err) => push('error', extractApiError(err)),
  });

  const send = useCallback(() => {
    const content = value.trim();
    if (!content || !userId) return;
    setValue('');

    const idempotencyKey = uuidv4();
    const frameId = uuidv4();

    const sent = sendFrame({
      type: 'SEND_MESSAGE',
      frame_id: frameId,
      payload: {
        conversation_id: conversationId,
        content_type: 'TEXT',
        content,
        idempotency_key: idempotencyKey,
      },
    });

    if (!sent) {
      restMutation.mutate(content);
    }

    requestAnimationFrame(() => inputRef.current?.focus());
  }, [value, userId, conversationId, sendFrame, restMutation]);

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  };

  const onInput = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setValue(e.target.value);
    e.target.style.height = 'auto';
    e.target.style.height = `${Math.min(e.target.scrollHeight, 160)}px`;
  };

  return (
    <div className="px-4 py-4 border-t border-ch-border bg-ch-surface">
      <div className="flex items-end gap-3 bg-ch-elevated border border-ch-border rounded-2xl px-4 py-3 focus-within:border-ch-accent/50 focus-within:shadow-[0_0_0_2px_rgba(245,158,11,0.08)] transition-all duration-150">
        <textarea
          ref={inputRef}
          rows={1}
          value={value}
          onChange={onInput}
          onKeyDown={onKeyDown}
          placeholder="Message…"
          aria-label="Message"
          className="flex-1 bg-transparent text-sm text-ch-text placeholder:text-ch-faint resize-none focus:outline-none leading-relaxed min-h-[20px] max-h-40"
          style={{ height: '20px' }}
        />
        <div className="flex items-center gap-2 pb-0.5">
          <span className="text-[10px] text-ch-faint font-body hidden sm:block">
            ↵ send · ⇧↵ newline
          </span>
          <button
            type="button"
            onClick={send}
            disabled={!value.trim()}
            className="w-8 h-8 rounded-xl bg-ch-accent text-ch-base flex items-center justify-center disabled:opacity-30 disabled:cursor-not-allowed hover:bg-ch-accent-dim transition-colors shadow-[0_0_12px_rgba(245,158,11,0.3)] disabled:shadow-none"
            aria-label="Send message"
          >
            <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 12L3.269 3.126A59.768 59.768 0 0121.485 12 59.77 59.77 0 013.27 20.876L5.999 12zm0 0h7.5" />
            </svg>
          </button>
        </div>
      </div>
    </div>
  );
}
