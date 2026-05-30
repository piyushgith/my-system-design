import { useAuthStore } from '../store/auth'
import { Card } from '../components/ui/Card'
import { Button } from '../components/ui/Button'
import { useNavigate } from 'react-router-dom'

export function DashboardPage() {
  const { user } = useAuthStore()
  const navigate = useNavigate()

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Dashboard</h1>
        <p className="mt-1 text-sm text-gray-500">
          Welcome back, {user?.name}. Manage notifications from here.
        </p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {(user?.role === 'admin' || user?.role === 'producer') && (
          <Card title="Send Notification">
            <p className="text-sm text-gray-600 mb-4">
              Submit a new notification to one or more channels for a recipient.
            </p>
            <Button onClick={() => navigate('/send')} size="sm">
              Send Now
            </Button>
          </Card>
        )}

        {(user?.role === 'admin' || user?.role === 'user') && (
          <Card title="In-App Inbox">
            <p className="text-sm text-gray-600 mb-4">
              View your in-app notifications and mark them as read.
            </p>
            <Button onClick={() => navigate('/inbox')} size="sm" variant="secondary">
              Open Inbox
            </Button>
          </Card>
        )}

        {(user?.role === 'admin' || user?.role === 'user') && (
          <Card title="Notification Preferences">
            <p className="text-sm text-gray-600 mb-4">
              Control which channels and categories you receive notifications on.
            </p>
            <Button onClick={() => navigate('/preferences')} size="sm" variant="secondary">
              Manage
            </Button>
          </Card>
        )}

        {user?.role === 'admin' && (
          <Card title="Template Management">
            <p className="text-sm text-gray-600 mb-4">
              Create, view, and deprecate notification templates across all channels.
            </p>
            <Button onClick={() => navigate('/templates')} size="sm" variant="secondary">
              Templates
            </Button>
          </Card>
        )}
      </div>

      <Card title="Quick Lookup">
        <NotificationLookup />
      </Card>
    </div>
  )
}

function NotificationLookup() {
  const navigate = useNavigate()

  function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const fd = new FormData(e.currentTarget)
    const id = (fd.get('notificationId') as string).trim()
    if (id) navigate(`/notifications/${id}`)
  }

  return (
    <form onSubmit={handleSubmit} className="flex gap-3">
      <input
        name="notificationId"
        placeholder="Paste notification ID (UUID)..."
        className="flex-1 rounded-md border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
      />
      <Button type="submit" variant="secondary">
        Look up
      </Button>
    </form>
  )
}
