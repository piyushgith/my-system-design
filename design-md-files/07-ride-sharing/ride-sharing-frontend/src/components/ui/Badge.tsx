import type { TripStatus } from '@/types/api/common.types'
import { TRIP_STATUS_COLORS, TRIP_STATUS_LABELS } from '@/constants/trip-status'

type BadgeProps = {
  status: TripStatus | string
  className?: string
}

export const Badge = ({ status, className = '' }: BadgeProps) => {
  const label = TRIP_STATUS_LABELS[status as TripStatus] ?? status
  const color = TRIP_STATUS_COLORS[status as TripStatus] ?? 'bg-slate-500'

  return (
    <span
      className={`inline-flex items-center rounded-full px-3 py-1 text-xs font-semibold uppercase tracking-wide text-white ${color} ${className}`}
    >
      {label}
    </span>
  )
}
