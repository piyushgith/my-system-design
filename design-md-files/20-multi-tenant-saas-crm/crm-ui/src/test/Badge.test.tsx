import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LeadStatusBadge, DealStatusBadge } from '@/components/ui/Badge'

describe('LeadStatusBadge', () => {
  it.each([
    ['NEW', 'NEW'],
    ['QUALIFIED', 'QUALIFIED'],
    ['CONVERTED', 'CONVERTED'],
    ['LOST', 'LOST'],
  ] as const)('renders status %s', (status, label) => {
    render(<LeadStatusBadge status={status} />)
    expect(screen.getByText(label)).toBeInTheDocument()
  })
})

describe('DealStatusBadge', () => {
  it.each([
    ['OPEN', 'OPEN'],
    ['WON', 'WON'],
    ['LOST', 'LOST'],
  ] as const)('renders status %s', (status, label) => {
    render(<DealStatusBadge status={status} />)
    expect(screen.getByText(label)).toBeInTheDocument()
  })
})
