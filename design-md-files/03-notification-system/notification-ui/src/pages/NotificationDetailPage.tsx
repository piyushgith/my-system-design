import { useParams, useNavigate } from 'react-router-dom'
import { useNotification, useCancelNotification } from '../hooks/useNotifications'
import { useToast } from '../components/ui/Toast'
import { Card } from '../components/ui/Card'
import { Button } from '../components/ui/Button'
import { Badge } from '../components/ui/Badge'
import { SpinnerPage } from '../components/ui/Spinner'
import { EmptyState } from '../components/ui/EmptyState'
import { NotificationStatusBadge } from '../components/notifications/StatusBadge'
import { DeliveryAttemptRow } from '../components/notifications/DeliveryAttemptRow'
import { formatDateTime, formatRelative } from '../utils/format'
import { CHANNEL_LABELS, CATEGORY_LABELS, PRIORITY_LABELS, PRIORITY_COLORS } from '../constants'
import { extractErrorMessage } from '../utils/errors'

export function NotificationDetailPage() {
  const { notificationId } = useParams<{ notificationId: string }>()
  const navigate = useNavigate()
  const { addToast } = useToast()
  const { data, isLoading, isError, error } = useNotification(notificationId)
  const { mutateAsync: cancel, isPending: cancelling } = useCancelNotification(notificationId!)

  if (isLoading) return <SpinnerPage />
  if (isError) {
    return (
      <EmptyState
        title="Notification not found"
        description={extractErrorMessage(error)}
        action={<Button onClick={() => navigate(-1)} variant="secondary">Go back</Button>}
      />
    )
  }
  if (!data) return null

  const cancellable = data.status === 'PENDING' || data.status === 'DISPATCHING'

  async function handleCancel() {
    try {
      await cancel()
      addToast('success', 'Notification cancelled')
    } catch (err) {
      addToast('error', extractErrorMessage(err))
    }
  }

  return (
    <div className="space-y-6 max-w-4xl">
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Notification Detail</h1>
          <p className="mt-1 font-mono text-sm text-gray-400">{data.notificationId}</p>
        </div>
        <div className="flex items-center gap-3">
          <NotificationStatusBadge status={data.status} />
          {cancellable && (
            <Button variant="danger" size="sm" loading={cancelling} onClick={handleCancel}>
              Cancel
            </Button>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <Card title="Overview">
          <dl className="space-y-3 text-sm">
            <Row label="Recipient" value={data.recipientUserId} mono />
            <Row label="Template" value={`${data.templateId} v${data.templateVersion ?? 'latest'}`} />
            <Row label="Category" value={CATEGORY_LABELS[data.category]} />
            <Row
              label="Priority"
              value={
                <Badge
                  label={PRIORITY_LABELS[data.priority]}
                  className={PRIORITY_COLORS[data.priority]}
                />
              }
            />
            {data.batchId && <Row label="Batch ID" value={data.batchId} mono />}
          </dl>
        </Card>

        <Card title="Timeline">
          <dl className="space-y-3 text-sm">
            <Row label="Created" value={formatDateTime(data.createdAt)} />
            <Row label="Scheduled" value={data.scheduledAt ? formatDateTime(data.scheduledAt) : 'Immediate'} />
            <Row label="Expires" value={data.expiresAt ? formatDateTime(data.expiresAt) : '—'} />
            <Row label="Dispatched" value={data.dispatchedAt ? formatDateTime(data.dispatchedAt) : '—'} />
            <Row label="Completed" value={data.completedAt ? formatRelative(data.completedAt) : '—'} />
          </dl>
        </Card>

        <Card title="Producer Context">
          <dl className="space-y-3 text-sm">
            <Row label="Service" value={data.producerService} />
            <Row label="Trace ID" value={data.producerTraceId} mono />
            <Row label="Idempotency Key" value={data.idempotencyKey} mono />
          </dl>
        </Card>

        {data.channelsOverride && data.channelsOverride.length > 0 && (
          <Card title="Channel Override">
            <div className="flex flex-wrap gap-2">
              {data.channelsOverride.map((c) => (
                <Badge
                  key={c}
                  label={CHANNEL_LABELS[c]}
                  className="bg-gray-100 text-gray-700"
                />
              ))}
            </div>
          </Card>
        )}
      </div>

      {Object.keys(data.variables).length > 0 && (
        <Card title="Template Variables">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-200 text-left text-xs font-medium text-gray-500 uppercase">
                  <th className="pb-2 pr-4">Key</th>
                  <th className="pb-2">Value</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(data.variables).map(([k, v]) => (
                  <tr key={k} className="border-b border-gray-100 last:border-0">
                    <td className="py-2 pr-4 font-mono text-gray-700">{k}</td>
                    <td className="py-2 text-gray-600">{v}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      <Card
        title={`Delivery Attempts (${data.deliveryAttempts?.length ?? 0})`}
      >
        {!data.deliveryAttempts?.length ? (
          <EmptyState title="No delivery attempts yet" description="The notification is being processed." />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-200 text-left text-xs font-medium text-gray-500 uppercase">
                  <th className="pb-2 pr-4">#</th>
                  <th className="pb-2 pr-4">Channel</th>
                  <th className="pb-2 pr-4">Provider</th>
                  <th className="pb-2 pr-4">Status</th>
                  <th className="pb-2 pr-4">Attempted At</th>
                  <th className="pb-2">Failure</th>
                </tr>
              </thead>
              <tbody>
                {data.deliveryAttempts.map((a) => (
                  <DeliveryAttemptRow key={a.attemptId} attempt={a} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  )
}

function Row({
  label,
  value,
  mono = false,
}: {
  label: string
  value: React.ReactNode
  mono?: boolean
}) {
  return (
    <div className="flex justify-between gap-4">
      <dt className="text-gray-500 shrink-0">{label}</dt>
      <dd className={`text-gray-900 text-right truncate ${mono ? 'font-mono text-xs' : ''}`}>
        {value}
      </dd>
    </div>
  )
}
