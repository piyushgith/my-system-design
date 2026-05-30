import { useParams, useNavigate } from 'react-router-dom'
import { useCustomer } from '../../hooks/useCustomer'
import { useAuthStore } from '../../store/authStore'
import { Card, CardHeader } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { Badge } from '../../components/ui/Badge'
import { LoadingSpinner } from '../../components/ui/LoadingSpinner'
import { ErrorAlert } from '../../components/ui/ErrorAlert'
import { formatDate } from '../../utils/date'

export function CustomerDetailPage() {
  const { cifId } = useParams<{ cifId: string }>()
  const navigate = useNavigate()
  const { role } = useAuthStore()
  const isTeller = role === 'TELLER'

  const { data: customer, isLoading, isError, error } = useCustomer(cifId)

  if (isLoading) {
    return (
      <div className="flex justify-center py-16">
        <LoadingSpinner size="lg" />
      </div>
    )
  }

  if (isError || !customer) {
    return (
      <div className="mx-auto max-w-xl space-y-4">
        <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>← Back</Button>
        <ErrorAlert message={error instanceof Error ? error.message : 'Customer not found.'} />
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div className="flex items-center gap-4">
        <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>← Back</Button>
        <h1 className="text-2xl font-bold text-slate-900">Customer Profile</h1>
      </div>

      <Card>
        <div className="flex items-start justify-between">
          <div>
            <h2 className="text-xl font-semibold text-slate-900">
              {customer.firstName} {customer.lastName}
            </h2>
            <p className="mt-0.5 font-mono text-sm text-slate-500">{customer.cifId}</p>
          </div>
          <div className="flex flex-col items-end gap-2">
            <Badge>{customer.customerStatus}</Badge>
            <Badge>{customer.kycStatus}</Badge>
          </div>
        </div>
      </Card>

      <Card>
        <CardHeader title="KYC Details" />
        <dl className="grid grid-cols-2 gap-x-6 gap-y-4 text-sm">
          <div>
            <dt className="text-slate-500">Date of Birth</dt>
            <dd className="mt-0.5 font-medium text-slate-900">{formatDate(customer.dateOfBirth)}</dd>
          </div>
          <div>
            <dt className="text-slate-500">KYC Status</dt>
            <dd className="mt-0.5"><Badge>{customer.kycStatus}</Badge></dd>
          </div>
          {customer.kycVerifiedAt && (
            <div>
              <dt className="text-slate-500">KYC Verified At</dt>
              <dd className="mt-0.5 font-medium text-slate-900">{formatDate(customer.kycVerifiedAt)}</dd>
            </div>
          )}
        </dl>
      </Card>

      {isTeller && (
        <Card>
          <CardHeader title="Actions" />
          <div className="flex gap-3">
            <Button onClick={() => navigate('/accounts/new', { state: { cifId: customer.cifId } })}>
              Open Account
            </Button>
            <Button variant="secondary" onClick={() => navigate('/transactions/deposit')}>
              Deposit
            </Button>
            <Button variant="secondary" onClick={() => navigate('/transactions/transfer')}>
              Transfer
            </Button>
          </div>
        </Card>
      )}
    </div>
  )
}
