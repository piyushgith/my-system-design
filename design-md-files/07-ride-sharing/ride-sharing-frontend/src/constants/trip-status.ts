import type { TripStatus } from '@/types/api/common.types'

export const TRIP_STATUS_LABELS: Record<TripStatus, string> = {
  REQUESTED: 'Requested',
  MATCHING: 'Finding driver',
  DRIVER_MATCHED: 'Driver on the way',
  DRIVER_ARRIVED: 'Driver arrived',
  IN_PROGRESS: 'Trip in progress',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  DISPUTED: 'Disputed',
  REASSIGNMENT: 'Reassigning',
}

export const TRIP_STATUS_COLORS: Record<TripStatus, string> = {
  REQUESTED: 'bg-slate-500',
  MATCHING: 'bg-amber-500 animate-pulse',
  DRIVER_MATCHED: 'bg-sky-500',
  DRIVER_ARRIVED: 'bg-emerald-500',
  IN_PROGRESS: 'bg-violet-500',
  COMPLETED: 'bg-emerald-600',
  CANCELLED: 'bg-rose-600',
  DISPUTED: 'bg-orange-600',
  REASSIGNMENT: 'bg-amber-600',
}

export const TRIP_STATUS_ORDER: TripStatus[] = [
  'REQUESTED',
  'MATCHING',
  'DRIVER_MATCHED',
  'DRIVER_ARRIVED',
  'IN_PROGRESS',
  'COMPLETED',
]
