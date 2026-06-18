import { useQuery } from '@tanstack/react-query'
import { tripService } from '@/api/services/trip.service'
import { riderService } from '@/api/services/rider.service'
import { driverService } from '@/api/services/driver.service'
import { ACTIVE_TRIP_STATUSES } from '@/constants/demo'
import type { TripStatus } from '@/types/api/common.types'

export const useActiveRiderTrip = () =>
  useQuery({
    queryKey: ['rider', 'trips', 'active'],
    queryFn: () => tripService.getActive(),
    refetchInterval: 5000,
  })

export const useDriverAvailability = () =>
  useQuery({
    queryKey: ['driver', 'availability'],
    queryFn: () => driverService.getAvailability(),
  })

export const useActiveTrip = (tripId: string | undefined) => {
  return useQuery({
    queryKey: ['trip', tripId],
    queryFn: () => tripService.get(tripId!),
    enabled: Boolean(tripId),
    refetchInterval: (query) => {
      const status = query.state.data?.status as TripStatus | undefined
      if (!status) return 2000
      return (ACTIVE_TRIP_STATUSES as readonly string[]).includes(status) ? 2000 : false
    },
  })
}

export const useTripHistory = () =>
  useQuery({
    queryKey: ['trips', 'history'],
    queryFn: () => tripService.history(),
  })

export const useRiderProfile = () =>
  useQuery({
    queryKey: ['rider', 'me'],
    queryFn: () => riderService.getProfile(),
  })
