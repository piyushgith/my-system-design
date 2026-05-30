import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { Badge } from '../../components/ui/Badge'

describe('Badge', () => {
  it('renders label', () => {
    render(<Badge label="Active" />)
    expect(screen.getByText('Active')).toBeInTheDocument()
  })

  it('applies custom className', () => {
    render(<Badge label="Test" className="bg-red-100 text-red-700" />)
    const el = screen.getByText('Test')
    expect(el.className).toContain('bg-red-100')
    expect(el.className).toContain('text-red-700')
  })
})
