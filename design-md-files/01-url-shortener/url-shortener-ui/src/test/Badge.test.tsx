import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Badge } from '@/components/ui/Badge'

describe('Badge', () => {
  it.each([
    ['green', 'bg-green-100'],
    ['red', 'bg-red-100'],
    ['yellow', 'bg-yellow-100'],
    ['blue', 'bg-blue-100'],
    ['gray', 'bg-gray-100'],
  ] as const)('renders %s variant with correct class', (variant, expectedClass) => {
    render(<Badge variant={variant}>Test</Badge>)
    const badge = screen.getByText('Test')
    expect(badge.className).toContain(expectedClass)
  })

  it('renders children', () => {
    render(<Badge>Active</Badge>)
    expect(screen.getByText('Active')).toBeInTheDocument()
  })
})
