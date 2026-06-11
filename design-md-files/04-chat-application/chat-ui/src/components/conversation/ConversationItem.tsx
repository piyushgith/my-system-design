import { Avatar } from '../ui/Avatar';
import { formatRelative, conversationDisplayName } from '../../utils/formatting';
import type { ConversationSummary } from '../../types';

interface Props {
  conversation: ConversationSummary;
  active: boolean;
  onClick: () => void;
}

const typeIcon = {
  DIRECT: (
    <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0" />
    </svg>
  ),
  GROUP: (
    <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M18 18.72a9.094 9.094 0 003.741-.479 3 3 0 00-4.682-2.72m.94 3.198l.001.031c0 .225-.012.447-.037.666A11.944 11.944 0 0112 21c-2.17 0-4.207-.576-5.963-1.584A6.062 6.062 0 016 18.719m12 0a5.971 5.971 0 00-.941-3.197m0 0A5.995 5.995 0 0012 12.75a5.995 5.995 0 00-5.058 2.772m0 0a3 3 0 00-4.681 2.72 8.986 8.986 0 003.74.477m.94-3.197a5.971 5.971 0 00-.94 3.197M15 6.75a3 3 0 11-6 0 3 3 0 016 0z" />
    </svg>
  ),
  CHANNEL: (
    <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M7 20l4-16m2 16l4-16M6 9h14M4 15h14" />
    </svg>
  ),
};

export function ConversationItem({ conversation, active, onClick }: Props) {
  const name = conversationDisplayName(conversation.name, conversation.type, conversation.conversationId);

  return (
    <button
      type="button"
      onClick={onClick}
      className={`
        w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-left transition-all duration-150
        ${active
          ? 'bg-ch-hover border border-ch-border shadow-[inset_0_0_0_1px_rgba(245,158,11,0.12)]'
          : 'hover:bg-ch-hover/60 border border-transparent'
        }
      `}
    >
      <Avatar name={name} size="sm" />
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-1.5">
          <span
            className={`text-xs font-body ${active ? 'text-ch-accent' : 'text-ch-faint'}`}
          >
            {typeIcon[conversation.type]}
          </span>
          <span
            className={`text-sm font-display font-semibold truncate ${
              active ? 'text-ch-accent' : 'text-ch-text'
            }`}
          >
            {name}
          </span>
        </div>
        <div className="flex items-center justify-between mt-0.5">
          <span className="text-xs text-ch-faint truncate">
            {conversation.memberCount} member{conversation.memberCount !== 1 ? 's' : ''}
          </span>
          {conversation.lastMessageAt && (
            <span className="text-xs text-ch-faint flex-shrink-0 ml-2">
              {formatRelative(conversation.lastMessageAt)}
            </span>
          )}
        </div>
      </div>
    </button>
  );
}
