import { Avatar } from '../ui/Avatar';
import { formatTime } from '../../utils/formatting';
import type { MessageResponse, MessageStatus } from '../../types';

interface Props {
  message: MessageResponse;
  isMine: boolean;
  showSender: boolean;
  senderName: string;
}

const statusIcons: Record<MessageStatus, React.ReactNode> = {
  SENT: (
    <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 12.75l6 6 9-13.5" />
    </svg>
  ),
  DELIVERED: (
    <svg className="w-3.5 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 12.75l6 6 9-13.5M4.5 12.75l4.5 4.5" />
    </svg>
  ),
  READ: (
    <svg className="w-3.5 h-3 text-ch-accent" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 12.75l6 6 9-13.5M4.5 12.75l4.5 4.5" />
    </svg>
  ),
};

export function MessageBubble({ message, isMine, showSender, senderName }: Props) {
  return (
    <div className={`flex gap-2.5 message-enter ${isMine ? 'flex-row-reverse' : 'flex-row'}`}>
      {!isMine && (
        <div className={`flex-shrink-0 ${showSender ? 'visible' : 'invisible'}`}>
          <Avatar name={senderName} size="xs" />
        </div>
      )}

      <div className={`flex flex-col max-w-[72%] ${isMine ? 'items-end' : 'items-start'}`}>
        {showSender && !isMine && (
          <span className="text-xs font-display font-semibold text-ch-muted mb-1 px-1">
            {senderName}
          </span>
        )}

        <div
          className={`
            relative px-3.5 py-2.5 rounded-2xl text-sm leading-relaxed
            ${isMine
              ? 'bg-ch-mine border border-ch-mine-border text-ch-text rounded-tr-sm'
              : 'bg-ch-elevated border border-ch-border text-ch-text rounded-tl-sm'
            }
          `}
        >
          {message.contentType === 'TEXT' ? (
            <p className="whitespace-pre-wrap break-words">{message.content}</p>
          ) : (
            <p className="whitespace-pre-wrap break-words font-mono text-xs">{message.content}</p>
          )}
        </div>

        <div className={`flex items-center gap-1 mt-1 px-1 ${isMine ? 'flex-row-reverse' : 'flex-row'}`}>
          <span className="text-[10px] text-ch-faint">
            {formatTime(message.sentAt)}
          </span>
          {isMine && (
            <span className="text-ch-faint">
              {statusIcons[message.status]}
            </span>
          )}
        </div>
      </div>
    </div>
  );
}
