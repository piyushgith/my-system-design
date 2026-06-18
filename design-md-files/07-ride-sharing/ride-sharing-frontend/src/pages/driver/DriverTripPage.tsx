import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { driverService } from '@/api/services/driver.service'
import { tripService } from '@/api/services/trip.service'
import { TERMINAL_TRIP_STATUSES } from '@/constants/demo'
import { useGeolocation } from '@/hooks/useGeolocation'
import { useUiStore } from '@/store/ui.store'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { TripMap } from '@/components/map/TripMap'
import { Input } from '@/components/ui/Input'
import { formatInr } from '@/utils/format'
import { getTripMapPoints } from '@/utils/trip-map'
import type { TripStatus } from '@/types/api/common.types'

export const DriverTripPage = () => {
  const { tripId } = useParams<{ tripId: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const addToast = useUiStore((s) => s.addToast)
  const { position } = useGeolocation()
  const [otp, setOtp] = useState('')

  const tripQuery = useQuery({
    queryKey: ['trip', tripId],
    queryFn: () => tripService.get(tripId!),
    enabled: Boolean(tripId),
    refetchInterval: (query) => {
      const status = query.state.data?.status as TripStatus | undefined
      if (!status) return 3000
      return (TERMINAL_TRIP_STATUSES as readonly string[]).includes(status) ? false : 3000
    },
  })

  const trip = tripQuery.data

  useEffect(() => {
    const interval = setInterval(() => {
      driverService.updateLocation({ lat: position.lat, lng: position.lng }).catch(() => {})
    }, 4000)
    return () => clearInterval(interval)
  }, [position.lat, position.lng])

  const arriveMutation = useMutation({
    mutationFn: () => driverService.arrive(tripId!),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['trip', tripId] }),
  })

  const startMutation = useMutation({
    mutationFn: () => driverService.startTrip(tripId!, { otp }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['trip', tripId] }),
  })

  const completeMutation = useMutation({
    mutationFn: () =>
      driverService.completeTrip(tripId!, {
        finalLat: trip!.destination_lat ?? position.lat,
        finalLng: trip!.destination_lng ?? position.lng,
      }),
    onSuccess: (data) => {
      addToast({ type: 'success', message: `Trip complete · ${formatInr(data.fare.total)}` })
      queryClient.invalidateQueries({ queryKey: ['driver', 'availability'] })
      navigate('/driver')
    },
  })

  if (tripQuery.isLoading) return <Spinner label="Loading trip…" className="py-20" />
  if (tripQuery.isError || !trip) {
    return (
      <div className="py-20 text-center text-muted">
        Trip not found
        <Button variant="secondary" className="mt-4" onClick={() => navigate('/driver')}>
          Back to home
        </Button>
      </div>
    )
  }

  const { pickup, destination } = getTripMapPoints(trip)

  return (
    <div className="animate-fade-in space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="font-display text-2xl font-bold text-cream">Active trip</h1>
        <Badge status={trip.status} />
      </div>

      <TripMap pickup={pickup} destination={destination} mapKey={trip.trip_id} className="h-[30vh]" />

      <Card>
        <p className="text-sm"><span className="text-muted">Pickup:</span> {trip.pickup_address}</p>
        <p className="text-sm"><span className="text-muted">Drop:</span> {trip.destination_address}</p>
      </Card>

      {trip.status === 'DRIVER_MATCHED' && (
        <Button className="w-full" loading={arriveMutation.isPending} onClick={() => arriveMutation.mutate()}>
          Arrived at pickup
        </Button>
      )}

      {trip.status === 'DRIVER_ARRIVED' && (
        <Card title="Start trip" subtitle="Enter rider OTP">
          <div className="space-y-3">
            <Input label="4-digit OTP" value={otp} onChange={(e) => setOtp(e.target.value)} maxLength={4} />
            <Button className="w-full" loading={startMutation.isPending} onClick={() => startMutation.mutate()}>
              Start trip
            </Button>
          </div>
        </Card>
      )}

      {trip.status === 'IN_PROGRESS' && (
        <Button className="w-full" loading={completeMutation.isPending} onClick={() => completeMutation.mutate()}>
          Complete trip at destination
        </Button>
      )}

      {trip.status === 'COMPLETED' && (
        <Button variant="secondary" className="w-full" onClick={() => navigate('/driver')}>
          Back to home
        </Button>
      )}
    </div>
  )
}
