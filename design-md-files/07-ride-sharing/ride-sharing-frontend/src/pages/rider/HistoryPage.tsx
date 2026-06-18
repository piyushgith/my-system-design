import { Link } from 'react-router-dom'
import { useTripHistory } from '@/hooks/useTripQueries'
import { ACTIVE_TRIP_STATUSES } from '@/constants/demo'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { EmptyState } from '@/components/ui/EmptyState'
import { Spinner } from '@/components/ui/Spinner'
import { formatDate, formatInr } from '@/utils/format'
import type { TripSummary } from '@/types/api/trip.types'

const getTripLink = (trip: TripSummary): string => {
  if ((ACTIVE_TRIP_STATUSES as readonly string[]).includes(trip.status)) {
    return `/rider/trip/${trip.trip_id}`
  }
  if (trip.status === 'COMPLETED' && !trip.rated_by_rider) {
    return `/rider/trip/${trip.trip_id}/rate`
  }
  return `/rider/trip/${trip.trip_id}`
}

export const HistoryPage = () => {
  const { data, isLoading, isError, refetch } = useTripHistory()

  if (isLoading) return <Spinner label="Loading history…" className="py-20" />

  if (isError) {
    return (
      <div className="py-20 text-center">
        <p className="text-muted">Could not load trip history</p>
        <Button variant="secondary" className="mt-4" onClick={() => refetch()}>
          Try again
        </Button>
      </div>
    )
  }

  const trips = data?.trips ?? []

  if (trips.length === 0) {
    return (
      <EmptyState
        title="No trips yet"
        description="Your completed rides will appear here"
        action={
          <Link to="/rider" className="text-amber-500 hover:underline">
            Book your first ride
          </Link>
        }
      />
    )
  }

  return (
    <div className="animate-fade-in space-y-4">
      <h1 className="font-display text-2xl font-bold text-cream">Trip history</h1>
      <ul className="space-y-3">
        {trips.map((trip) => (
          <li key={trip.trip_id}>
            <Link to={getTripLink(trip)}>
              <Card className="transition hover:border-amber-500/30">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0 flex-1">
                    <p className="truncate font-medium text-cream">{trip.destination_address}</p>
                    <p className="truncate text-sm text-muted">{trip.pickup_address}</p>
                    <p className="mt-1 text-xs text-muted">{formatDate(trip.requested_at)}</p>
                  </div>
                  <div className="flex flex-col items-end gap-2">
                    <Badge status={trip.status} />
                    {trip.final_fare != null && (
                      <span className="text-sm font-semibold text-amber-400">{formatInr(trip.final_fare)}</span>
                    )}
                  </div>
                </div>
              </Card>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  )
}
