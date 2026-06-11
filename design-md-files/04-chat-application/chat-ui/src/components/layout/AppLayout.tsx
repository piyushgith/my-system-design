import { useState } from 'react';
import { useWebSocket } from '../../hooks/useWebSocket';
import { ConversationList } from '../conversation/ConversationList';
import { MessageList } from '../message/MessageList';
import { MessageInput } from '../message/MessageInput';
import { useQuery } from '@tanstack/react-query';
import { conversationsApi } from '../../api/conversations';
import { presenceApi } from '../../api/presence';
import { Avatar } from '../ui/Avatar';
import { conversationDisplayName } from '../../utils/formatting';
import type { PresenceStatus } from '../../types';

export function AppLayout() {
  const [activeId, setActiveId] = useState<string | null>(null);
  const { sendFrame } = useWebSocket();
  const [showSidebar, setShowSidebar] = useState(false);

  const { data: conv } = useQuery({
    queryKey: ['conversation', activeId],
    queryFn: () => conversationsApi.get(activeId!),
    enabled: !!activeId,
    staleTime: 60_000,
  });

  const memberIds = conv?.members.map((m) => m.userId) ?? [];
  const { data: presenceData } = useQuery({
    queryKey: ['presence', memberIds.join(',')],
    queryFn: () => presenceApi.query(memberIds),
    enabled: memberIds.length > 0,
    refetchInterval: 15_000,
  });

  const presence = presenceData?.presence ?? {};
  const onlineCount = Object.values(presence).filter((p) => p.status === 'ONLINE').length;

  const convName = conv
    ? conversationDisplayName(conv.name, conv.type, conv.conversationId)
    : null;

  return (
    <div className="flex h-full bg-ch-base">
      {/* Mobile sidebar overlay */}
      {showSidebar && (
        <button
          type="button"
          className="fixed inset-0 z-30 w-full h-full bg-black/60 md:hidden cursor-default"
          aria-label="Close sidebar"
          onClick={() => setShowSidebar(false)}
        />
      )}

      {/* Conversation sidebar */}
      <div
        className={`
          fixed inset-y-0 left-0 z-30 w-72 transform transition-transform duration-200
          md:relative md:translate-x-0 md:flex-shrink-0
          ${showSidebar ? 'translate-x-0' : '-translate-x-full'}
        `}
      >
        <ConversationList
          activeConversationId={activeId}
          onSelect={(id) => {
            setActiveId(id);
            setShowSidebar(false);
          }}
        />
      </div>

      {/* Main chat area */}
      <div className="flex-1 flex flex-col min-w-0">
        {activeId ? (
          <>
            {/* Chat header */}
            <div className="h-14 flex items-center gap-3 px-4 border-b border-ch-border bg-ch-surface flex-shrink-0">
              <button
                type="button"
                onClick={() => setShowSidebar(true)}
                className="md:hidden text-ch-muted hover:text-ch-text transition-colors mr-1"
                aria-label="Open sidebar"
              >
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 6.75h16.5M3.75 12h16.5m-16.5 5.25h16.5" />
                </svg>
              </button>

              {convName && <Avatar name={convName} size="sm" />}

              <div className="flex-1 min-w-0">
                <p className="font-display font-semibold text-ch-text text-sm truncate">
                  {convName ?? '—'}
                </p>
                {conv && (
                  <p className="text-[11px] text-ch-faint">
                    {conv.members.length} member{conv.members.length !== 1 ? 's' : ''}
                    {onlineCount > 0 && (
                      <span className="text-ch-online ml-2">· {onlineCount} online</span>
                    )}
                  </p>
                )}
              </div>

              {/* Member presence dots */}
              <div className="hidden sm:flex items-center gap-1">
                {conv?.members.slice(0, 5).map((m) => {
                  const p = presence[m.userId];
                  return (
                    <div
                      key={m.userId}
                      className="relative w-2 h-2 rounded-full"
                      style={{
                        backgroundColor:
                          (p?.status as PresenceStatus) === 'ONLINE'
                            ? '#22c55e'
                            : (p?.status as PresenceStatus) === 'AWAY'
                            ? '#eab308'
                            : '#4a4a68',
                      }}
                      title={`${m.userId.slice(0, 8)} — ${p?.status ?? 'OFFLINE'}`}
                    />
                  );
                })}
              </div>
            </div>

            <MessageList conversationId={activeId} />
            <MessageInput conversationId={activeId} sendFrame={sendFrame} />
          </>
        ) : (
          <div className="flex-1 flex flex-col items-center justify-center gap-4 text-center p-8">
            <button
              type="button"
              onClick={() => setShowSidebar(true)}
              className="md:hidden mb-2 text-sm text-ch-accent underline underline-offset-2"
            >
              Open conversations
            </button>
            <div className="w-20 h-20 rounded-3xl bg-ch-surface border border-ch-border flex items-center justify-center">
              <svg className="w-9 h-9 text-ch-faint" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1} d="M8.625 9.75a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H8.25m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H12m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0h-.375m-13.5 3.01c0 1.6 1.123 2.994 2.707 3.227 1.087.16 2.185.283 3.293.369V21l4.184-4.183a1.14 1.14 0 01.778-.332 48.294 48.294 0 005.83-.498c1.585-.233 2.708-1.626 2.708-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0012 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018z" />
              </svg>
            </div>
            <div>
              <p className="font-display font-bold text-lg text-ch-text">Select a conversation</p>
              <p className="text-sm text-ch-faint mt-1">Or start a new one with the + button</p>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
