import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { driverService } from '@/api/services/driver.service'
import { useUiStore } from '@/store/ui.store'
import { Badge } from '@/components/ui/Badge'
import { Card } from '@/components/ui/Card'
import { EmptyState } from '@/components/ui/EmptyState'
import { Spinner } from '@/components/ui/Spinner'
import { Button } from '@/components/ui/Button'

export const PendingPage = () => {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const addToast = useUiStore((s) => s.addToast)
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['driver', 'pending'],
    queryFn: () => driverService.getPending(),
    refetchInterval: 5000,
  })

  const acceptMutation = useMutation({
    mutationFn: (tripId: string) => driverService.acceptTrip(tripId),
    onSuccess: (result) => {
      addToast({ type: 'success', message: 'Trip accepted!' })
      queryClient.invalidateQueries({ queryKey: ['driver', 'pending'] })
      navigate(`/driver/trip/${result.trip_id}`)
    },
  })

  if (isLoading) return <Spinner label="Loading…" className="py-20" />

  if (isError) {
    return (
      <div className="py-20 text-center">
        <p className="text-muted">Could not load pending trips</p>
        <Button variant="secondary" className="mt-4" onClick={() => refetch()}>
          Try again
        </Button>
      </div>
    )
  }

  const trips = data ?? []

  if (trips.length === 0) {
    return (
      <EmptyState
        title="No pending trips"
        description="Matching trips in your city will appear here"
        action={
          <Button variant="secondary" onClick={() => navigate('/driver')}>
            Back to home
          </Button>
        }
      />
    )
  }

  return (
    <div className="animate-fade-in space-y-4">
      <h1 className="font-display text-2xl font-bold text-cream">Pending trips</h1>
      <ul className="space-y-3">
        {trips.map((trip) => (
          <li key={trip.trip_id}>
            <Card>
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <p className="font-medium text-cream">{trip.pickup_address}</p>
                  <p className="text-sm text-muted">→ {trip.destination_address}</p>
                </div>
                <Badge status={trip.status} />
              </div>
              <Button
                className="mt-4 w-full"
                loading={acceptMutation.isPending}
                onClick={() => acceptMutation.mutate(trip.trip_id)}
              >
                Accept trip
              </Button>
            </Card>
          </li>
        ))}
      </ul>
    </div>
  )
}
