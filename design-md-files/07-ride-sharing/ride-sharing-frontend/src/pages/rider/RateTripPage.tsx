import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { tripService } from '@/api/services/trip.service'
import { useUiStore } from '@/store/ui.store'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Input } from '@/components/ui/Input'
import { rateTripSchema, type RateTripForm } from '@/features/rider/schemas/booking.schema'

export const RateTripPage = () => {
  const { tripId } = useParams<{ tripId: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const addToast = useUiStore((s) => s.addToast)
  const [score, setScore] = useState(5)

  const form = useForm<RateTripForm>({
    resolver: zodResolver(rateTripSchema),
    defaultValues: { score: 5, comment: '' },
  })

  const mutation = useMutation({
    mutationFn: (values: RateTripForm) => tripService.rate(tripId!, values),
    onSuccess: () => {
      addToast({ type: 'success', message: 'Thanks for your feedback!' })
      queryClient.invalidateQueries({ queryKey: ['trips', 'history'] })
      queryClient.invalidateQueries({ queryKey: ['trip', tripId] })
      navigate('/rider/history')
    },
  })

  const handleSubmit = form.handleSubmit((values) => {
    mutation.mutate({ ...values, score })
  })

  return (
    <div className="animate-fade-in space-y-6">
      <h1 className="font-display text-2xl font-bold text-cream">Rate your trip</h1>

      <Card title="How was your ride?">
        <form onSubmit={handleSubmit} className="space-y-6">
          <div className="flex justify-center gap-2" role="group" aria-label="Rating">
            {[1, 2, 3, 4, 5].map((n) => (
              <button
                key={n}
                type="button"
                onClick={() => {
                  setScore(n)
                  form.setValue('score', n)
                }}
                className={`text-3xl transition ${n <= score ? 'text-amber-400' : 'text-muted/30'}`}
                aria-label={`${n} stars`}
              >
                ★
              </button>
            ))}
          </div>
          <Input label="Comment (optional)" {...form.register('comment')} />
          <Button type="submit" className="w-full" loading={mutation.isPending}>
            Submit rating
          </Button>
          <Button type="button" variant="ghost" className="w-full" onClick={() => navigate('/rider')}>
            Skip
          </Button>
        </form>
      </Card>
    </div>
  )
}
