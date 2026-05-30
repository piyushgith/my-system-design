import type { InAppInboxItem } from '../../types'
import { formatRelative } from '../../utils/format'
import { Button } from '../ui/Button'

interface InboxItemProps {
  item: InAppInboxItem
  onMarkRead: (id: string) => Promise<void>
  marking: boolean
}

export function InboxItemCard({ item, onMarkRead, marking }: InboxItemProps) {
  return (
    <div
      className={`flex gap-4 rounded-lg border p-4 transition-colors
        ${item.isRead ? 'border-gray-200 bg-white' : 'border-blue-200 bg-blue-50'}`}
    >
      {!item.isRead && (
        <div className="mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full bg-blue-500" />
      )}
      {item.isRead && <div className="mt-1.5 h-2.5 w-2.5 shrink-0" />}

      <div className="flex-1 min-w-0">
        <div className="flex items-start justify-between gap-2">
          <p className="text-sm font-semibold text-gray-900">{item.title}</p>
          <span className="shrink-0 text-xs text-gray-400">{formatRelative(item.createdAt)}</span>
        </div>
        <p className="mt-0.5 text-sm text-gray-600 line-clamp-2">{item.body}</p>
        {item.actionUrl && (
          <a
            href={item.actionUrl}
            className="mt-1 inline-block text-xs text-blue-600 hover:underline"
          >
            View →
          </a>
        )}
      </div>

      {!item.isRead && (
        <Button
          variant="ghost"
          size="sm"
          loading={marking}
          onClick={() => onMarkRead(item.inboxItemId)}
          className="shrink-0"
        >
          Mark read
        </Button>
      )}
    </div>
  )
}
