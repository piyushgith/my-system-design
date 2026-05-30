import { Badge } from '../ui/Badge'
import {
  NOTIFICATION_STATUS_COLORS,
  NOTIFICATION_STATUS_LABELS,
  DELIVERY_STATUS_COLORS,
  DELIVERY_STATUS_LABELS,
} from '../../constants'
import type { NotificationStatus, DeliveryStatus } from '../../types'

export function NotificationStatusBadge({ status }: { status: NotificationStatus }) {
  return (
    <Badge
      label={NOTIFICATION_STATUS_LABELS[status]}
      className={NOTIFICATION_STATUS_COLORS[status]}
    />
  )
}

export function DeliveryStatusBadge({ status }: { status: DeliveryStatus }) {
  return (
    <Badge
      label={DELIVERY_STATUS_LABELS[status]}
      className={DELIVERY_STATUS_COLORS[status]}
    />
  )
}
