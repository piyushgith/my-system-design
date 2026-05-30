import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { NotificationStatusBadge, DeliveryStatusBadge } from '../../components/notifications/StatusBadge'
import { NOTIFICATION_STATUS_LABELS, DELIVERY_STATUS_LABELS } from '../../constants'
import type { NotificationStatus, DeliveryStatus } from '../../types'

describe('NotificationStatusBadge', () => {
  const statuses: NotificationStatus[] = [
    'PENDING', 'DISPATCHING', 'DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED', 'EXPIRED',
  ]

  statuses.forEach((status) => {
    it(`renders label for ${status}`, () => {
      render(<NotificationStatusBadge status={status} />)
      expect(screen.getByText(NOTIFICATION_STATUS_LABELS[status])).toBeInTheDocument()
    })
  })
})

describe('DeliveryStatusBadge', () => {
  const statuses: DeliveryStatus[] = ['PENDING', 'IN_FLIGHT', 'DELIVERED', 'FAILED', 'BOUNCED']

  statuses.forEach((status) => {
    it(`renders label for ${status}`, () => {
      render(<DeliveryStatusBadge status={status} />)
      expect(screen.getByText(DELIVERY_STATUS_LABELS[status])).toBeInTheDocument()
    })
  })
})
