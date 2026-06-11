import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { conversationsApi } from '../../api/conversations';
import { ConversationItem } from './ConversationItem';
import { NewConversationModal } from './NewConversationModal';
import { useAuthStore } from '../../store/authStore';
import { Avatar } from '../ui/Avatar';

interface Props {
  activeConversationId: string | null;
  onSelect: (id: string) => void;
}

export function ConversationList({ activeConversationId, onSelect }: Props) {
  const user = useAuthStore((s) => s.user);
  const clearUser = useAuthStore((s) => s.clearUser);
  const [showModal, setShowModal] = useState(false);

  const { data, isLoading } = useQuery({
    queryKey: ['conversations'],
    queryFn: () => conversationsApi.list(50),
    refetchInterval: 30_000,
  });

  const handleCreated = (id: string) => {
    setShowModal(false);
    onSelect(id);
  };

  return (
    <>
      <div className="flex flex-col h-full bg-ch-surface border-r border-ch-border">
        {/* Header */}
        <div className="px-4 pt-5 pb-3 border-b border-ch-border-subtle">
          <div className="flex items-center justify-between mb-4">
            <h1 className="font-display font-bold text-xl shimmer-text">
              Obsidian
            </h1>
            <button
              type="button"
              onClick={() => setShowModal(true)}
              className="w-8 h-8 rounded-lg bg-ch-elevated border border-ch-border flex items-center justify-center text-ch-muted hover:text-ch-accent hover:border-ch-accent/50 transition-all duration-150"
              aria-label="New conversation"
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
              </svg>
            </button>
          </div>

          <div className="relative">
            <svg
              className="absolute left-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-ch-faint"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth={2}
            >
              <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" />
            </svg>
            <input
              type="search"
              placeholder="Search conversations"
              aria-label="Search conversations"
              className="w-full bg-ch-elevated border border-ch-border rounded-lg pl-8 pr-3 py-2 text-xs text-ch-text placeholder:text-ch-faint focus:outline-none focus:border-ch-accent/50 transition-colors"
            />
          </div>
        </div>

        {/* Conversations */}
        <div className="flex-1 overflow-y-auto px-2 py-2 space-y-0.5">
          {isLoading && (
            <div className="flex flex-col gap-2 px-2 py-2">
              {Array.from({ length: 5 }).map((_, i) => (
                <div key={i} className="h-14 bg-ch-elevated rounded-xl animate-pulse" />
              ))}
            </div>
          )}

          {!isLoading && (!data?.conversations || data.conversations.length === 0) && (
            <div className="flex flex-col items-center gap-3 py-12 text-center">
              <div className="w-12 h-12 rounded-2xl bg-ch-elevated border border-ch-border flex items-center justify-center">
                <svg className="w-5 h-5 text-ch-faint" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M8.625 12a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H8.25m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H12m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0h-.375M21 12c0 4.556-4.03 8.25-9 8.25a9.764 9.764 0 01-2.555-.337A5.972 5.972 0 015.41 20.97a5.969 5.969 0 01-.474-.065 4.48 4.48 0 00.978-2.025c.09-.457-.133-.901-.467-1.226C3.93 16.178 3 14.189 3 12c0-4.556 4.03-8.25 9-8.25s9 3.694 9 8.25z" />
                </svg>
              </div>
              <div>
                <p className="text-sm font-display font-semibold text-ch-text">No conversations yet</p>
                <p className="text-xs text-ch-faint mt-1">Press + to start one</p>
              </div>
            </div>
          )}

          {data?.conversations.map((conv) => (
            <ConversationItem
              key={conv.conversationId}
              conversation={conv}
              active={conv.conversationId === activeConversationId}
              onClick={() => onSelect(conv.conversationId)}
            />
          ))}
        </div>

        {/* User footer */}
        <div className="px-3 py-3 border-t border-ch-border-subtle flex items-center gap-3">
          <Avatar name={user?.displayName ?? '?'} size="sm" />
          <div className="flex-1 min-w-0">
            <p className="text-sm font-display font-semibold text-ch-text truncate">
              {user?.displayName}
            </p>
            <p className="text-xs text-ch-faint truncate">@{user?.username}</p>
          </div>
          <button
            type="button"
            onClick={clearUser}
            className="text-ch-faint hover:text-ch-error transition-colors"
            aria-label="Logout"
            title="Logout"
          >
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 9V5.25A2.25 2.25 0 0013.5 3h-6a2.25 2.25 0 00-2.25 2.25v13.5A2.25 2.25 0 007.5 21h6a2.25 2.25 0 002.25-2.25V15M12 9l-3 3m0 0l3 3m-3-3h12.75" />
            </svg>
          </button>
        </div>
      </div>

      {showModal && (
        <NewConversationModal onClose={() => setShowModal(false)} onCreated={handleCreated} />
      )}
    </>
  );
}
