import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { pricingService } from '@/api/services/pricing.service'
import { tripService } from '@/api/services/trip.service'
import { getApiErrorDetail, isApiErrorCode } from '@/api/errors'
import { DEMO_CITY_ID, DEMO_DESTINATION, DEMO_PICKUP } from '@/constants/demo'
import { useActiveRiderTrip } from '@/hooks/useTripQueries'
import { useUiStore } from '@/store/ui.store'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Input } from '@/components/ui/Input'
import { Spinner } from '@/components/ui/Spinner'
import { TripMap } from '@/components/map/TripMap'
import { formatInr, formatDistance, formatDuration } from '@/utils/format'
import type { FareEstimateResponse } from '@/types/api/pricing.types'
import type { VehicleType } from '@/types/api/common.types'

const VEHICLE_TYPES: VehicleType[] = ['ECONOMY', 'PREMIUM', 'SUV', 'AUTO', 'BIKE']

export const RiderHomePage = () => {
  const navigate = useNavigate()
  const addToast = useUiStore((s) => s.addToast)
  const { data: activeTrip, isLoading: activeTripLoading } = useActiveRiderTrip()
  const [vehicleType, setVehicleType] = useState<VehicleType>('ECONOMY')
  const [pickupAddress, setPickupAddress] = useState(DEMO_PICKUP.address)
  const [destinationAddress, setDestinationAddress] = useState(DEMO_DESTINATION.address)
  const [quote, setQuote] = useState<FareEstimateResponse | null>(null)

  useEffect(() => {
    setQuote(null)
  }, [vehicleType])

  useEffect(() => {
    if (activeTrip?.trip_id) {
      navigate(`/rider/trip/${activeTrip.trip_id}`, { replace: true })
    }
  }, [activeTrip?.trip_id, navigate])

  const estimateMutation = useMutation({
    mutationFn: () =>
      pricingService.estimate({
        pickupLat: DEMO_PICKUP.lat,
        pickupLng: DEMO_PICKUP.lng,
        destinationLat: DEMO_DESTINATION.lat,
        destinationLng: DEMO_DESTINATION.lng,
        vehicleType,
        cityId: DEMO_CITY_ID,
      }),
    onSuccess: (data) => {
      setQuote(data)
      addToast({ type: 'success', message: 'Fare estimate ready' })
    },
  })

  const bookMutation = useMutation({
    mutationFn: () =>
      tripService.create({
        quoteId: quote!.quote_id,
        pickupAddress,
        destinationAddress,
      }),
    onSuccess: (trip) => {
      addToast({ type: 'success', message: 'Trip requested — finding driver…' })
      navigate(`/rider/trip/${trip.trip_id}`)
    },
    onError: (error) => {
      if (isApiErrorCode(error, 'TRIP_ALREADY_ACTIVE')) {
        const tripId = getApiErrorDetail(error, 'trip_id')
        addToast({ type: 'info', message: 'You already have an active trip' })
        if (tripId) navigate(`/rider/trip/${tripId}`)
      }
    },
  })

  if (activeTripLoading || activeTrip?.trip_id) {
    return <Spinner label="Checking for active trip…" className="py-20" />
  }

  return (
    <div className="animate-fade-in space-y-6">
      <div>
        <h1 className="font-display text-2xl font-bold text-cream">Book a ride</h1>
        <p className="text-sm text-muted">MG Road → Koramangala · Bangalore demo</p>
      </div>

      <TripMap
        pickup={DEMO_PICKUP}
        destination={DEMO_DESTINATION}
        mapKey="rider-home"
        className="h-[40vh] lg:h-[320px]"
      />

      <Card title="Trip details">
        <div className="space-y-4">
          <Input label="Pickup" value={pickupAddress} onChange={(e) => setPickupAddress(e.target.value)} />
          <Input label="Destination" value={destinationAddress} onChange={(e) => setDestinationAddress(e.target.value)} />

          <div>
            <p className="mb-2 text-xs font-medium uppercase tracking-wider text-muted">Vehicle</p>
            <div className="flex flex-wrap gap-2">
              {VEHICLE_TYPES.map((vt) => (
                <button
                  key={vt}
                  type="button"
                  onClick={() => setVehicleType(vt)}
                  className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition ${
                    vehicleType === vt ? 'bg-amber-500 text-charcoal' : 'bg-surface-elevated text-muted'
                  }`}
                >
                  {vt}
                </button>
              ))}
            </div>
          </div>

          <Button
            className="w-full"
            variant="secondary"
            loading={estimateMutation.isPending}
            onClick={() => estimateMutation.mutate()}
          >
            Get fare estimate
          </Button>
        </div>
      </Card>

      {quote && (
        <Card title="Estimate" subtitle={`Expires ${new Date(quote.expires_at).toLocaleTimeString()}`}>
          <div className="space-y-3">
            <div className="flex items-baseline justify-between">
              <span className="font-display text-3xl font-bold text-amber-400">
                {formatInr(quote.fare_min)} – {formatInr(quote.fare_max)}
              </span>
              {quote.surge_active && (
                <span className="rounded-full bg-rose-500/20 px-2 py-0.5 text-xs font-bold text-rose-400">
                  {quote.surge_multiplier}x surge
                </span>
              )}
            </div>
            <p className="text-sm text-muted">
              {formatDistance(quote.estimated_distance_km)} · {formatDuration(quote.estimated_duration_min)}
            </p>
            <Button className="w-full" loading={bookMutation.isPending} onClick={() => bookMutation.mutate()}>
              Request {vehicleType} ride
            </Button>
          </div>
        </Card>
      )}
    </div>
  )
}
