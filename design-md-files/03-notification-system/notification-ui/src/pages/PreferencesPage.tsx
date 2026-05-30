import { useAuthStore } from '../store/auth'
import { usePreferences, useUpdatePreferences } from '../hooks/usePreferences'
import { PreferenceToggle } from '../components/preferences/PreferenceToggle'
import { Card } from '../components/ui/Card'
import { SpinnerPage } from '../components/ui/Spinner'
import { EmptyState } from '../components/ui/EmptyState'
import { useToast } from '../components/ui/Toast'
import { extractErrorMessage } from '../utils/errors'
import { ALL_CHANNELS, CHANNEL_LABELS } from '../constants'
import type { Channel, UserNotificationPreference } from '../types'

export function PreferencesPage() {
  const { user } = useAuthStore()
  const { addToast } = useToast()

  const { data: prefs, isLoading, isError, error } = usePreferences(user!.userId)
  const { mutateAsync: update, isPending } = useUpdatePreferences(user!.userId)

  async function handleToggle(pref: UserNotificationPreference, optedIn: boolean) {
    try {
      await update({
        preferences: [
          {
            channel: pref.channel,
            category: pref.category,
            optedIn,
          },
        ],
      })
      addToast('success', `${CHANNEL_LABELS[pref.channel]} preference updated`)
    } catch (err) {
      addToast('error', extractErrorMessage(err))
    }
  }

  if (isLoading) return <SpinnerPage />
  if (isError) {
    return (
      <EmptyState
        title="Failed to load preferences"
        description={extractErrorMessage(error)}
      />
    )
  }
  if (!prefs) return null

  const byChannel = groupByChannel(prefs)

  return (
    <div className="space-y-6 max-w-3xl">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Notification Preferences</h1>
        <p className="mt-1 text-sm text-gray-500">
          Control which channels and categories deliver notifications to you.
        </p>
      </div>

      <div className="rounded-md border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
        Channels marked with a lock were hard-unsubscribed after a provider bounce and cannot be
        re-enabled from this interface. Contact support to restore.
      </div>

      {ALL_CHANNELS.map((channel) => {
        const channelPrefs = byChannel[channel] ?? []
        if (!channelPrefs.length) return null
        return (
          <Card key={channel} title={CHANNEL_LABELS[channel]}>
            <div className="divide-y divide-gray-100">
              {channelPrefs.map((pref) => (
                <div key={`${pref.channel}-${pref.category}`} className="py-3 first:pt-0 last:pb-0">
                  <PreferenceToggle
                    pref={pref}
                    onChange={(optedIn) => handleToggle(pref, optedIn)}
                    disabled={isPending}
                  />
                </div>
              ))}
            </div>
          </Card>
        )
      })}
    </div>
  )
}

function groupByChannel(
  prefs: UserNotificationPreference[]
): Record<Channel, UserNotificationPreference[]> {
  const map: Record<string, UserNotificationPreference[]> = {}
  for (const p of prefs) {
    if (!map[p.channel]) map[p.channel] = []
    map[p.channel].push(p)
  }
  return map as Record<Channel, UserNotificationPreference[]>
}
