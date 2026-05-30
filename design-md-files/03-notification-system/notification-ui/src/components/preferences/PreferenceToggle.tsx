import type { UserNotificationPreference } from '../../types'
import { CHANNEL_LABELS, CATEGORY_LABELS } from '../../constants'

interface Props {
  pref: UserNotificationPreference
  onChange: (optedIn: boolean) => void
  disabled?: boolean
}

export function PreferenceToggle({ pref, onChange, disabled = false }: Props) {
  const isLocked = pref.hardUnsubscribed

  return (
    <div className="flex items-center justify-between rounded-lg border border-gray-200 bg-white p-4">
      <div>
        <p className="text-sm font-medium text-gray-900">
          {CHANNEL_LABELS[pref.channel]} — {CATEGORY_LABELS[pref.category]}
        </p>
        {isLocked && (
          <p className="mt-0.5 text-xs text-red-500">
            Hard unsubscribed (provider bounce) — cannot re-enable
          </p>
        )}
        {pref.quietHoursStart && pref.quietHoursEnd && (
          <p className="mt-0.5 text-xs text-gray-500">
            Quiet hours: {pref.quietHoursStart} – {pref.quietHoursEnd} ({pref.timezone})
          </p>
        )}
      </div>

      <button
        type="button"
        disabled={disabled || isLocked}
        onClick={() => onChange(!pref.optedIn)}
        className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors
          focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2
          disabled:cursor-not-allowed disabled:opacity-50
          ${pref.optedIn ? 'bg-blue-600' : 'bg-gray-200'}`}
      >
        <span
          className={`inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform
            ${pref.optedIn ? 'translate-x-6' : 'translate-x-1'}`}
        />
      </button>
    </div>
  )
}
