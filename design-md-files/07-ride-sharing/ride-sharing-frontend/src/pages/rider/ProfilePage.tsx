import { useForm } from 'react-hook-form'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { riderService } from '@/api/services/rider.service'
import { useRiderProfile } from '@/hooks/useTripQueries'
import { useUiStore } from '@/store/ui.store'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Input } from '@/components/ui/Input'
import { Spinner } from '@/components/ui/Spinner'

type ProfileForm = { fullName: string; email: string }

export const ProfilePage = () => {
  const { data: profile, isLoading, isError, refetch } = useRiderProfile()
  const queryClient = useQueryClient()
  const addToast = useUiStore((s) => s.addToast)

  const form = useForm<ProfileForm>({
    values: profile
      ? { fullName: profile.full_name, email: profile.email ?? '' }
      : undefined,
  })

  const mutation = useMutation({
    mutationFn: (values: ProfileForm) =>
      riderService.updateProfile({ fullName: values.fullName, email: values.email || undefined }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['rider', 'me'] })
      addToast({ type: 'success', message: 'Profile updated' })
    },
  })

  if (isLoading) return <Spinner label="Loading profile…" className="py-20" />

  if (isError) {
    return (
      <div className="py-20 text-center">
        <p className="text-muted">Could not load profile</p>
        <Button variant="secondary" className="mt-4" onClick={() => refetch()}>
          Try again
        </Button>
      </div>
    )
  }

  return (
    <div className="animate-fade-in space-y-6">
      <h1 className="font-display text-2xl font-bold text-cream">Profile</h1>

      <Card title={profile?.full_name} subtitle={profile?.phone_number}>
        <div className="mb-4 flex gap-6 text-sm">
          <div>
            <p className="text-muted">Rating</p>
            <p className="font-semibold text-cream">★ {profile?.rating}</p>
          </div>
          <div>
            <p className="text-muted">Trips</p>
            <p className="font-semibold text-cream">{profile?.total_trips}</p>
          </div>
          <div>
            <p className="text-muted">Status</p>
            <p className="font-semibold text-cream">{profile?.status}</p>
          </div>
        </div>

        <form onSubmit={form.handleSubmit((v) => mutation.mutate(v))} className="space-y-4">
          <Input label="Full name" {...form.register('fullName')} />
          <Input label="Email" type="email" {...form.register('email')} />
          <Button type="submit" loading={mutation.isPending} className="w-full">
            Save changes
          </Button>
        </form>
      </Card>
    </div>
  )
}
