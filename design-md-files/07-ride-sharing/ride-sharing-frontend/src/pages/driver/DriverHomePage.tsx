import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { driverService } from '@/api/services/driver.service'
import { DEMO_CITY_ID, DEMO_PICKUP, DEMO_VEHICLE_ID } from '@/constants/demo'
import { useGeolocation } from '@/hooks/useGeolocation'
import { useDriverAvailability } from '@/hooks/useTripQueries'
import { useUiStore } from '@/store/ui.store'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Badge } from '@/components/ui/Badge'
import { formatInr } from '@/utils/format'

export const DriverHomePage = () => {
  const navigate = useNavigate()
  const addToast = useUiStore((s) => s.addToast)
  const { position } = useGeolocation(DEMO_PICKUP)
  const [isOnline, setIsOnline] = useState(false)
  const availabilityQuery = useDriverAvailability()

  useEffect(() => {
    const status = availabilityQuery.data?.status
    if (!status) return
    setIsOnline(status === 'AVAILABLE' || status === 'ON_TRIP')
    if (status === 'ON_TRIP' && availabilityQuery.data?.current_trip_id) {
      navigate(`/driver/trip/${availabilityQuery.data.current_trip_id}`, { replace: true })
    }
  }, [availabilityQuery.data, navigate])

  const onlineMutation = useMutation({
    mutationFn: () =>
      driverService.goOnline({
        vehicleId: DEMO_VEHICLE_ID,
        cityId: DEMO_CITY_ID,
        lat: position.lat,
        lng: position.lng,
      }),
    onSuccess: () => {
      setIsOnline(true)
      availabilityQuery.refetch()
      addToast({ type: 'success', message: 'You are online' })
    },
  })

  const offlineMutation = useMutation({
    mutationFn: () => driverService.goOffline(),
    onSuccess: () => {
      setIsOnline(false)
      availabilityQuery.refetch()
      addToast({ type: 'info', message: 'You are offline' })
    },
  })

  const offerQuery = useQuery({
    queryKey: ['driver', 'offer'],
    queryFn: () => driverService.getOffer(),
    enabled: isOnline,
    refetchInterval: isOnline ? 2000 : false,
    retry: false,
  })

  const offer = offerQuery.data
  const hasOffer = Boolean(offer?.trip_id)

  const acceptMutation = useMutation({
    mutationFn: (tripId: string) => driverService.acceptTrip(tripId),
    onSuccess: (data) => {
      addToast({ type: 'success', message: 'Trip accepted!' })
      navigate(`/driver/trip/${data.trip_id}`)
    },
  })

  const rejectMutation = useMutation({
    mutationFn: (tripId: string) => driverService.rejectTrip(tripId),
    onSuccess: () => {
      offerQuery.refetch()
      addToast({ type: 'info', message: 'Offer rejected' })
    },
  })

  useEffect(() => {
    if (!isOnline) return
    const interval = setInterval(() => {
      driverService.updateLocation({ lat: position.lat, lng: position.lng }).catch(() => {})
    }, 4000)
    return () => clearInterval(interval)
  }, [isOnline, position.lat, position.lng])

  return (
    <div className="animate-fade-in space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="font-display text-2xl font-bold text-cream">Driver hub</h1>
        <span className={`h-3 w-3 rounded-full ${isOnline ? 'bg-emerald-400 animate-pulse' : 'bg-muted'}`} aria-label={isOnline ? 'Online' : 'Offline'} />
      </div>

      <Card title="Availability">
        {!isOnline ? (
          <Button className="w-full" loading={onlineMutation.isPending} onClick={() => onlineMutation.mutate()}>
            Go online
          </Button>
        ) : (
          <Button variant="secondary" className="w-full" loading={offlineMutation.isPending} onClick={() => offlineMutation.mutate()}>
            Go offline
          </Button>
        )}
        <p className="mt-2 text-xs text-muted">
          Location: {position.lat.toFixed(4)}, {position.lng.toFixed(4)}
        </p>
      </Card>

      {isOnline && (
        <Card title="Current offer" subtitle="Auto-refreshes every 2s · 15s to accept">
          {offerQuery.isLoading ? (
            <p className="text-sm text-muted">Checking for rides…</p>
          ) : offerQuery.isError ? (
            <p className="text-sm text-rose-400">Could not load offers. Retrying…</p>
          ) : hasOffer ? (
            <div className="space-y-4">
              <div className="rounded-xl border border-amber-500/30 bg-amber-500/5 p-4">
                <Badge status="MATCHING" />
                <p className="mt-2 font-medium text-cream">{offer!.pickup_address}</p>
                <p className="text-sm text-muted">→ {offer!.destination_address}</p>
                <p className="mt-2 text-lg font-bold text-amber-400">{formatInr(offer!.estimated_fare_min!)}</p>
                {offer!.expires_at && (
                  <p className="text-xs text-muted">Expires {new Date(offer!.expires_at).toLocaleTimeString()}</p>
                )}
              </div>
              <div className="flex gap-3">
                <Button
                  className="flex-1"
                  loading={acceptMutation.isPending}
                  onClick={() => acceptMutation.mutate(offer!.trip_id!)}
                >
                  Accept
                </Button>
                <Button
                  variant="secondary"
                  className="flex-1"
                  loading={rejectMutation.isPending}
                  onClick={() => rejectMutation.mutate(offer!.trip_id!)}
                >
                  Reject
                </Button>
              </div>
            </div>
          ) : (
            <p className="text-sm text-muted">Waiting for ride requests…</p>
          )}
        </Card>
      )}
    </div>
  )
}
