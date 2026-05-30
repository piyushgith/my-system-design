import type { DeliveryAttempt } from '../../types'
import { DeliveryStatusBadge } from './StatusBadge'
import { Badge } from '../ui/Badge'
import { CHANNEL_LABELS } from '../../constants'
import { formatDateTime } from '../../utils/format'

export function DeliveryAttemptRow({ attempt }: { attempt: DeliveryAttempt }) {
  return (
    <tr className="border-b border-gray-100 last:border-0">
      <td className="py-3 pr-4 text-sm text-gray-900">#{attempt.attemptNumber}</td>
      <td className="py-3 pr-4">
        <Badge label={CHANNEL_LABELS[attempt.channel]} className="bg-gray-100 text-gray-700" />
      </td>
      <td className="py-3 pr-4 text-sm text-gray-600">{attempt.provider}</td>
      <td className="py-3 pr-4">
        <DeliveryStatusBadge status={attempt.status} />
      </td>
      <td className="py-3 pr-4 text-sm text-gray-500">
        {formatDateTime(attempt.attemptedAt)}
      </td>
      <td className="py-3 text-sm text-red-600">
        {attempt.failureReason ?? '—'}
      </td>
    </tr>
  )
}
