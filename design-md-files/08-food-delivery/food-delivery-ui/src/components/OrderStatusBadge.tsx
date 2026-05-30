import { Badge } from './ui/Badge'
import type { OrderStatus } from '../types/order'

const statusConfig: Record<OrderStatus, { label: string; variant: 'green' | 'red' | 'orange' | 'blue' | 'gray' | 'purple' }> = {
  PAYMENT_PENDING: { label: 'Payment Pending', variant: 'orange' },
  PAYMENT_CONFIRMED: { label: 'Payment Confirmed', variant: 'blue' },
  PAYMENT_FAILED: { label: 'Payment Failed', variant: 'red' },
  RESTAURANT_NOTIFIED: { label: 'Sent to Restaurant', variant: 'blue' },
  RESTAURANT_ACCEPTED: { label: 'Accepted', variant: 'green' },
  RESTAURANT_REJECTED: { label: 'Rejected', variant: 'red' },
  FOOD_BEING_PREPARED: { label: 'Preparing', variant: 'orange' },
  FOOD_READY: { label: 'Food Ready', variant: 'green' },
  DELIVERY_PARTNER_ASSIGNED: { label: 'Partner Assigned', variant: 'blue' },
  PARTNER_AT_RESTAURANT: { label: 'Partner at Restaurant', variant: 'purple' },
  PICKED_UP: { label: 'Picked Up', variant: 'purple' },
  OUT_FOR_DELIVERY: { label: 'Out for Delivery', variant: 'orange' },
  DELIVERED: { label: 'Delivered', variant: 'green' },
  CANCELLING: { label: 'Cancelling', variant: 'gray' },
  CANCELLED: { label: 'Cancelled', variant: 'red' },
}

interface Props {
  status: OrderStatus
  size?: 'sm' | 'md'
}

export function OrderStatusBadge({ status, size = 'sm' }: Props) {
  const config = statusConfig[status] ?? { label: status, variant: 'gray' as const }
  return <Badge label={config.label} variant={config.variant} size={size} />
}
