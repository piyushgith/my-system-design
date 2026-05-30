import type { Channel, Category, Priority, NotificationStatus, DeliveryStatus } from '../types'

export const CHANNEL_LABELS: Record<Channel, string> = {
  EMAIL: 'Email',
  SMS: 'SMS',
  PUSH: 'Push',
  IN_APP: 'In-App',
}

export const CATEGORY_LABELS: Record<Category, string> = {
  TRANSACTIONAL: 'Transactional',
  MARKETING: 'Marketing',
  PRODUCT_UPDATE: 'Product Update',
  SECURITY: 'Security',
}

export const PRIORITY_LABELS: Record<Priority, string> = {
  CRITICAL: 'Critical',
  HIGH: 'High',
  NORMAL: 'Normal',
  LOW: 'Low',
}

export const NOTIFICATION_STATUS_LABELS: Record<NotificationStatus, string> = {
  PENDING: 'Pending',
  DISPATCHING: 'Dispatching',
  DELIVERED: 'Delivered',
  PARTIALLY_DELIVERED: 'Partially Delivered',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
}

export const DELIVERY_STATUS_LABELS: Record<DeliveryStatus, string> = {
  PENDING: 'Pending',
  IN_FLIGHT: 'In Flight',
  DELIVERED: 'Delivered',
  FAILED: 'Failed',
  BOUNCED: 'Bounced',
}

export const NOTIFICATION_STATUS_COLORS: Record<NotificationStatus, string> = {
  PENDING: 'bg-yellow-100 text-yellow-800',
  DISPATCHING: 'bg-blue-100 text-blue-800',
  DELIVERED: 'bg-green-100 text-green-800',
  PARTIALLY_DELIVERED: 'bg-orange-100 text-orange-800',
  FAILED: 'bg-red-100 text-red-800',
  CANCELLED: 'bg-gray-100 text-gray-800',
  EXPIRED: 'bg-gray-100 text-gray-600',
}

export const DELIVERY_STATUS_COLORS: Record<DeliveryStatus, string> = {
  PENDING: 'bg-yellow-100 text-yellow-800',
  IN_FLIGHT: 'bg-blue-100 text-blue-800',
  DELIVERED: 'bg-green-100 text-green-800',
  FAILED: 'bg-red-100 text-red-800',
  BOUNCED: 'bg-orange-100 text-orange-800',
}

export const PRIORITY_COLORS: Record<Priority, string> = {
  CRITICAL: 'bg-red-100 text-red-800',
  HIGH: 'bg-orange-100 text-orange-800',
  NORMAL: 'bg-blue-100 text-blue-800',
  LOW: 'bg-gray-100 text-gray-600',
}

export const ALL_CHANNELS: Channel[] = ['EMAIL', 'SMS', 'PUSH', 'IN_APP']
export const ALL_CATEGORIES: Category[] = [
  'TRANSACTIONAL',
  'MARKETING',
  'PRODUCT_UPDATE',
  'SECURITY',
]
export const ALL_PRIORITIES: Priority[] = ['CRITICAL', 'HIGH', 'NORMAL', 'LOW']
