import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import { PreferenceToggle } from '../../components/preferences/PreferenceToggle'
import type { UserNotificationPreference } from '../../types'

const base: UserNotificationPreference = {
  userId: 'user-1',
  channel: 'EMAIL',
  category: 'TRANSACTIONAL',
  optedIn: true,
  timezone: 'UTC',
  hardUnsubscribed: false,
  updatedAt: new Date().toISOString(),
}

describe('PreferenceToggle', () => {
  it('renders channel and category labels', () => {
    render(<PreferenceToggle pref={base} onChange={vi.fn()} />)
    expect(screen.getByText(/Email.*Transactional/i)).toBeInTheDocument()
  })

  it('calls onChange with false when toggled off', async () => {
    const handler = vi.fn()
    render(<PreferenceToggle pref={base} onChange={handler} />)
    await userEvent.click(screen.getByRole('button'))
    expect(handler).toHaveBeenCalledWith(false)
  })

  it('calls onChange with true when toggled on', async () => {
    const handler = vi.fn()
    render(<PreferenceToggle pref={{ ...base, optedIn: false }} onChange={handler} />)
    await userEvent.click(screen.getByRole('button'))
    expect(handler).toHaveBeenCalledWith(true)
  })

  it('disables toggle when hardUnsubscribed', async () => {
    const handler = vi.fn()
    render(<PreferenceToggle pref={{ ...base, hardUnsubscribed: true }} onChange={handler} />)
    const btn = screen.getByRole('button')
    expect(btn).toBeDisabled()
    await userEvent.click(btn)
    expect(handler).not.toHaveBeenCalled()
  })

  it('shows hard-unsubscribe warning when locked', () => {
    render(<PreferenceToggle pref={{ ...base, hardUnsubscribed: true }} onChange={vi.fn()} />)
    expect(screen.getByText(/hard unsubscribed/i)).toBeInTheDocument()
  })

  it('disables toggle when disabled prop set', () => {
    render(<PreferenceToggle pref={base} onChange={vi.fn()} disabled />)
    expect(screen.getByRole('button')).toBeDisabled()
  })
})
