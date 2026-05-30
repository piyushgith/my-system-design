import { useState } from 'react'
import { useAuthStore } from '../store/auth'
import { useInbox, useMarkAsRead, useMarkAllAsRead } from '../hooks/useInbox'
import { InboxItemCard } from '../components/inbox/InboxItem'
import { Card } from '../components/ui/Card'
import { Button } from '../components/ui/Button'
import { SpinnerPage } from '../components/ui/Spinner'
import { EmptyState } from '../components/ui/EmptyState'
import { useToast } from '../components/ui/Toast'
import { extractErrorMessage } from '../utils/errors'

export function InboxPage() {
  const { user } = useAuthStore()
  const { addToast } = useToast()
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [page, setPage] = useState(0)
  const size = 20

  const { data, isLoading, isError, error } = useInbox(user!.userId, {
    unreadOnly,
    page,
    size,
  })

  const { mutateAsync: markRead, isPending: markingRead } = useMarkAsRead(user!.userId)
  const { mutateAsync: markAllRead, isPending: markingAll } = useMarkAllAsRead(user!.userId)

  async function handleMarkRead(inboxItemId: string) {
    try {
      await markRead(inboxItemId)
    } catch (err) {
      addToast('error', extractErrorMessage(err))
    }
  }

  async function handleMarkAll() {
    try {
      await markAllRead()
      addToast('success', 'All messages marked as read')
    } catch (err) {
      addToast('error', extractErrorMessage(err))
    }
  }

  const totalPages = data ? data.totalPages : 0
  const unreadCount = data?.content.filter((i) => !i.isRead).length ?? 0

  return (
    <div className="space-y-6 max-w-3xl">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">In-App Inbox</h1>
          <p className="mt-1 text-sm text-gray-500">
            {data ? `${data.totalElements} messages` : '—'}
            {unreadCount > 0 && `, ${unreadCount} unread on this page`}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <label className="flex items-center gap-2 text-sm cursor-pointer select-none">
            <input
              type="checkbox"
              checked={unreadOnly}
              onChange={(e) => {
                setUnreadOnly(e.target.checked)
                setPage(0)
              }}
              className="rounded border-gray-300 text-blue-600"
            />
            Unread only
          </label>
          <Button
            variant="secondary"
            size="sm"
            loading={markingAll}
            onClick={handleMarkAll}
            disabled={!data?.content.some((i) => !i.isRead)}
          >
            Mark all read
          </Button>
        </div>
      </div>

      <Card>
        {isLoading ? (
          <SpinnerPage />
        ) : isError ? (
          <EmptyState
            title="Failed to load inbox"
            description={extractErrorMessage(error)}
          />
        ) : !data?.content.length ? (
          <EmptyState
            title={unreadOnly ? 'No unread messages' : 'Inbox is empty'}
            description={unreadOnly ? 'All caught up!' : 'In-app notifications will appear here.'}
          />
        ) : (
          <div className="divide-y divide-gray-100">
            {data.content.map((item) => (
              <InboxItemCard
                key={item.inboxItemId}
                item={item}
                onMarkRead={handleMarkRead}
                marking={markingRead}
              />
            ))}
          </div>
        )}
      </Card>

      {totalPages > 1 && (
        <div className="flex items-center justify-between">
          <Button
            variant="secondary"
            size="sm"
            disabled={page === 0}
            onClick={() => setPage((p) => p - 1)}
          >
            Previous
          </Button>
          <span className="text-sm text-gray-500">
            Page {page + 1} of {totalPages}
          </span>
          <Button
            variant="secondary"
            size="sm"
            disabled={page >= totalPages - 1}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </Button>
        </div>
      )}
    </div>
  )
}
