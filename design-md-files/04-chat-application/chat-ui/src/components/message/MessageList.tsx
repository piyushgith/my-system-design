import { useEffect, useRef, useCallback } from 'react';
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { messagesApi } from '../../api/messages';
import { MessageBubble } from './MessageBubble';
import { formatDate } from '../../utils/formatting';
import { useAuthStore } from '../../store/authStore';
import { shortUserId } from '../../utils/formatting';
import type { MessageResponse } from '../../types';

interface Props {
  conversationId: string;
}

function DateDivider({ date }: { date: string }) {
  return (
    <div className="flex items-center gap-3 my-4">
      <div className="flex-1 h-px bg-ch-border" />
      <span className="text-[10px] font-display font-semibold uppercase tracking-widest text-ch-faint px-2">
        {date}
      </span>
      <div className="flex-1 h-px bg-ch-border" />
    </div>
  );
}

export function MessageList({ conversationId }: Props) {
  const userId = useAuthStore((s) => s.user?.userId);
  const bottomRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const queryClient = useQueryClient();

  const { data, fetchNextPage, hasNextPage, isFetchingNextPage, isLoading } = useInfiniteQuery({
    queryKey: ['messages-paged', conversationId],
    queryFn: ({ pageParam }) =>
      messagesApi.history(conversationId, pageParam as number | undefined, 50),
    getNextPageParam: (lastPage) =>
      lastPage.hasMore ? (lastPage.oldestSeq ?? undefined) : undefined,
    initialPageParam: undefined as number | undefined,
    staleTime: Infinity,
  });

  // Also keep flat list in cache for WS updates
  useEffect(() => {
    if (!data) return;
    const all = data.pages.flatMap((p) => [...p.messages].reverse());
    queryClient.setQueryData<MessageResponse[]>(['messages', conversationId], all);
  }, [data, conversationId, queryClient]);

  // Merge WS-pushed messages
  const wsMsgs = queryClient.getQueryData<MessageResponse[]>(['messages', conversationId]) ?? [];

  const pagedMsgs = data?.pages.flatMap((p) => [...p.messages].reverse()) ?? [];
  const pagedIds = new Set(pagedMsgs.map((m) => m.messageId));
  const newMsgs = wsMsgs.filter((m) => !pagedIds.has(m.messageId));
  const allMessages = [...pagedMsgs, ...newMsgs];

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [allMessages.length]);

  const onScroll = useCallback(() => {
    const el = containerRef.current;
    if (!el) return;
    if (el.scrollTop < 80 && hasNextPage && !isFetchingNextPage) {
      fetchNextPage();
    }
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

  if (isLoading) {
    return (
      <div className="flex-1 flex flex-col gap-3 p-4">
        {Array.from({ length: 8 }).map((_, i) => (
          <div
            key={i}
            className={`h-10 rounded-2xl bg-ch-elevated animate-pulse ${i % 3 === 0 ? 'self-end w-48' : 'w-64'}`}
          />
        ))}
      </div>
    );
  }

  if (allMessages.length === 0) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center p-8">
        <div className="w-16 h-16 rounded-2xl bg-ch-elevated border border-ch-border flex items-center justify-center">
          <svg className="w-7 h-7 text-ch-faint" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M8.625 9.75a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H8.25m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H12m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0h-.375m-13.5 3.01c0 1.6 1.123 2.994 2.707 3.227 1.087.16 2.185.283 3.293.369V21l4.184-4.183a1.14 1.14 0 01.778-.332 48.294 48.294 0 005.83-.498c1.585-.233 2.708-1.626 2.708-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0012 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018z" />
          </svg>
        </div>
        <div>
          <p className="font-display font-semibold text-ch-text">No messages yet</p>
          <p className="text-xs text-ch-faint mt-1">Be the first to say something</p>
        </div>
      </div>
    );
  }

  let lastDate = '';

  return (
    <div
      ref={containerRef}
      onScroll={onScroll}
      className="flex-1 overflow-y-auto px-4 py-4 flex flex-col gap-1"
    >
      {isFetchingNextPage && (
        <div className="flex justify-center py-2">
          <div className="w-4 h-4 border-2 border-ch-accent border-t-transparent rounded-full animate-spin" />
        </div>
      )}

      {allMessages.map((msg, idx) => {
        const msgDate = formatDate(msg.sentAt);
        const showDate = msgDate !== lastDate;
        if (showDate) lastDate = msgDate;

        const prevMsg = allMessages[idx - 1];
        const showSender = !prevMsg || prevMsg.senderId !== msg.senderId;

        return (
          <div key={msg.messageId}>
            {showDate && <DateDivider date={msgDate} />}
            <MessageBubble
              message={msg}
              isMine={msg.senderId === userId}
              showSender={showSender}
              senderName={shortUserId(msg.senderId)}
            />
          </div>
        );
      })}
      <div ref={bottomRef} />
    </div>
  );
}
