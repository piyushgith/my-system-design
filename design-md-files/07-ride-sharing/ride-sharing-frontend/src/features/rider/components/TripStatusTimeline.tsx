import type { TripStatus } from '@/types/api/common.types'
import { TRIP_STATUS_ORDER, TRIP_STATUS_LABELS } from '@/constants/trip-status'

type TripStatusTimelineProps = {
  status: TripStatus
}

const TERMINAL_NON_PROGRESS: TripStatus[] = ['CANCELLED', 'DISPUTED', 'REASSIGNMENT']

export const TripStatusTimeline = ({ status }: TripStatusTimelineProps) => {
  if (TERMINAL_NON_PROGRESS.includes(status)) {
    return (
      <p className="rounded-xl bg-rose-500/10 px-3 py-2 text-sm text-rose-300">
        {TRIP_STATUS_LABELS[status]}
      </p>
    )
  }

  const currentIndex = TRIP_STATUS_ORDER.indexOf(status)

  return (
    <ol className="flex flex-col gap-2" aria-label="Trip progress">
      {TRIP_STATUS_ORDER.map((step, index) => {
        const done = currentIndex >= 0 && index <= currentIndex
        const active = step === status
        return (
          <li
            key={step}
            className={`flex items-center gap-3 rounded-xl px-3 py-2 text-sm transition ${
              active ? 'bg-amber-500/10 text-amber-300' : done ? 'text-cream' : 'text-muted/50'
            }`}
          >
            <span
              className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-bold ${
                done ? 'bg-amber-500 text-charcoal' : 'border border-border'
              }`}
              aria-hidden="true"
            >
              {done ? '✓' : index + 1}
            </span>
            {TRIP_STATUS_LABELS[step]}
          </li>
        )
      })}
    </ol>
  )
}
