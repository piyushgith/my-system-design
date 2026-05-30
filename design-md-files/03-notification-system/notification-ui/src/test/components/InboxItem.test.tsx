import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import { InboxItemCard } from '../../components/inbox/InboxItem'
import type { InAppInboxItem } from '../../types'

const base: InAppInboxItem = {
  inboxItemId: 'item-1',
  notificationId: 'notif-1',
  userId: 'user-1',
  title: 'Order shipped',
  body: 'Your order #123 has been shipped.',
  isRead: false,
  createdAt: new Date().toISOString(),
}

describe('InboxItemCard', () => {
  it('renders title and body', () => {
    render(<InboxItemCard item={base} onMarkRead={vi.fn()} marking={false} />)
    expect(screen.getByText('Order shipped')).toBeInTheDocument()
    expect(screen.getByText(/Your order #123/)).toBeInTheDocument()
  })

  it('shows mark-read button for unread items', () => {
    render(<InboxItemCard item={base} onMarkRead={vi.fn()} marking={false} />)
    expect(screen.getByRole('button', { name: /mark read/i })).toBeInTheDocument()
  })

  it('calls onMarkRead with item id', async () => {
    const handler = vi.fn().mockResolvedValue(undefined)
    render(<InboxItemCard item={base} onMarkRead={handler} marking={false} />)
    await userEvent.click(screen.getByRole('button', { name: /mark read/i }))
    expect(handler).toHaveBeenCalledWith('item-1')
  })

  it('hides mark-read button for read items', () => {
    render(<InboxItemCard item={{ ...base, isRead: true }} onMarkRead={vi.fn()} marking={false} />)
    expect(screen.queryByRole('button', { name: /mark read/i })).not.toBeInTheDocument()
  })

  it('renders action URL link when present', () => {
    render(<InboxItemCard item={{ ...base, actionUrl: 'https://example.com' }} onMarkRead={vi.fn()} marking={false} />)
    expect(screen.getByRole('link', { name: /view/i })).toHaveAttribute('href', 'https://example.com')
  })
})
