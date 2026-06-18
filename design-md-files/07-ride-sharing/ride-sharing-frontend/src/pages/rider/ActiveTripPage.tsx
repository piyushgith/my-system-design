import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { tripService } from '@/api/services/trip.service'
import { useActiveTrip } from '@/hooks/useTripQueries'
import { TERMINAL_TRIP_STATUSES } from '@/constants/demo'
import { useUiStore } from '@/store/ui.store'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { TripMap } from '@/components/map/TripMap'
import { TripStatusTimeline } from '@/features/rider/components/TripStatusTimeline'
import { formatInr } from '@/utils/format'
import { getTripMapPoints } from '@/utils/trip-map'
import { useEffect } from 'react'

export const ActiveTripPage = () => {
  const { tripId } = useParams<{ tripId: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const addToast = useUiStore((s) => s.addToast)
  const { data: trip, isLoading, isError } = useActiveTrip(tripId)

  useEffect(() => {
    if (trip?.status === 'COMPLETED' && !trip.rated_by_rider) {
      navigate(`/rider/trip/${tripId}/rate`, { replace: true })
    }
  }, [trip?.status, trip?.rated_by_rider, tripId, navigate])

  useEffect(() => {
    if (trip?.status === 'CANCELLED') {
      addToast({ type: 'info', message: 'This trip was cancelled' })
      navigate('/rider', { replace: true })
    }
  }, [trip?.status, navigate, addToast])

  const cancelMutation = useMutation({
    mutationFn: () => tripService.cancel(tripId!, { reason: 'CHANGED_MIND' }),
    onSuccess: () => {
      addToast({ type: 'info', message: 'Trip cancelled' })
      queryClient.invalidateQueries({ queryKey: ['trip', tripId] })
      queryClient.invalidateQueries({ queryKey: ['trips', 'history'] })
      queryClient.invalidateQueries({ queryKey: ['rider', 'trips', 'active'] })
      navigate('/rider', { replace: true })
    },
  })

  if (isLoading) return <Spinner label="Loading trip…" className="py-20" />
  if (isError || !trip) {
    return (
      <div className="py-20 text-center text-muted">
        Trip not found
        <Button variant="secondary" className="mt-4" onClick={() => navigate('/rider')}>
          Back to home
        </Button>
      </div>
    )
  }

  const canCancel = !(TERMINAL_TRIP_STATUSES as readonly string[]).includes(trip.status) && trip.status !== 'IN_PROGRESS'
  const isReadOnly = trip.status === 'COMPLETED' || trip.status === 'CANCELLED'
  const { pickup, destination } = getTripMapPoints(trip)

  return (
    <div className="animate-fade-in space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="font-display text-2xl font-bold text-cream">
          {isReadOnly ? 'Trip summary' : 'Your trip'}
        </h1>
        <Badge status={trip.status} />
      </div>

      <TripMap
        pickup={pickup}
        destination={destination}
        driver={trip.driver_location ? { lat: trip.driver_location.lat, lng: trip.driver_location.lng } : null}
        mapKey={trip.trip_id}
        className="h-[35vh]"
      />

      <Card>
        <div className="space-y-2 text-sm">
          <p><span className="text-muted">From:</span> {trip.pickup_address}</p>
          <p><span className="text-muted">To:</span> {trip.destination_address}</p>
          <p><span className="text-muted">Fare:</span> {formatInr(trip.estimated_fare.min)} – {formatInr(trip.estimated_fare.max)}</p>
        </div>
      </Card>

      {trip.driver && (
        <Card title="Your driver">
          <p className="font-semibold text-cream">{trip.driver.name}</p>
          <p className="text-sm text-muted">
            ★ {trip.driver.rating} · {trip.driver.vehicle.color} {trip.driver.vehicle.make} {trip.driver.vehicle.model}
          </p>
          <p className="text-xs text-muted">{trip.driver.vehicle.plate}</p>
        </Card>
      )}

      {trip.status === 'DRIVER_ARRIVED' && trip.otp && (
        <Card title="Start code" subtitle="Share with your driver">
          <p className="font-display text-4xl font-bold tracking-[0.5em] text-amber-400" aria-live="polite">
            {trip.otp}
          </p>
        </Card>
      )}

      {!isReadOnly && (
        <Card title="Progress">
          <TripStatusTimeline status={trip.status} />
        </Card>
      )}

      {trip.status === 'COMPLETED' && trip.rated_by_rider && (
        <Button variant="secondary" className="w-full" onClick={() => navigate('/rider/history')}>
          Back to history
        </Button>
      )}

      {canCancel && (
        <Button variant="danger" className="w-full" loading={cancelMutation.isPending} onClick={() => cancelMutation.mutate()}>
          Cancel trip
        </Button>
      )}
    </div>
  )
}
