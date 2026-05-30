import { useBalance } from '../../../hooks/useAccount'
import { Card, CardHeader } from '../../../components/ui/Card'
import { Badge } from '../../../components/ui/Badge'
import { LoadingSpinner } from '../../../components/ui/LoadingSpinner'
import { formatCurrency } from '../../../utils/currency'
import { formatDate } from '../../../utils/date'
import type { AccountResponse } from '../../../types'

interface Props {
  account: AccountResponse
}

export function OverviewTab({ account }: Props) {
  const { data: balance, isLoading: balanceLoading } = useBalance(account.accountId)

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <BalanceTile
          label="Current Balance"
          value={balance?.currentBalance ?? account.currentBalance}
          loading={balanceLoading}
          highlight
        />
        <BalanceTile
          label="Available Balance"
          value={balance?.availableBalance ?? account.availableBalance}
          loading={balanceLoading}
        />
        <BalanceTile
          label="Lien Hold"
          value={account.liensTotal}
          negative={account.liensTotal > 0}
        />
      </div>

      <Card>
        <CardHeader title="Account Details" />
        <dl className="grid grid-cols-2 gap-x-6 gap-y-4 text-sm">
          <div>
            <dt className="text-slate-500">Account ID</dt>
            <dd className="mt-0.5 font-mono font-medium text-slate-900">{account.accountId}</dd>
          </div>
          <div>
            <dt className="text-slate-500">CIF ID</dt>
            <dd className="mt-0.5 font-mono font-medium text-slate-900">{account.cifId}</dd>
          </div>
          <div>
            <dt className="text-slate-500">Account Type</dt>
            <dd className="mt-0.5 font-medium text-slate-900">{account.accountType}</dd>
          </div>
          <div>
            <dt className="text-slate-500">Status</dt>
            <dd className="mt-0.5"><Badge>{account.status}</Badge></dd>
          </div>
          <div>
            <dt className="text-slate-500">Currency</dt>
            <dd className="mt-0.5 font-medium text-slate-900">{account.currency}</dd>
          </div>
          <div>
            <dt className="text-slate-500">Open Date</dt>
            <dd className="mt-0.5 font-medium text-slate-900">{formatDate(account.openDate)}</dd>
          </div>
          {account.productCode && (
            <div>
              <dt className="text-slate-500">Product Code</dt>
              <dd className="mt-0.5 font-medium text-slate-900">{account.productCode}</dd>
            </div>
          )}
          <div>
            <dt className="text-slate-500">KYC Status</dt>
            <dd className="mt-0.5"><Badge>{account.kycStatus}</Badge></dd>
          </div>
        </dl>
      </Card>

      {balance && (
        <p className="text-xs text-slate-400 text-right">
          Balance as of {balance.balanceAsOf} · auto-refreshes every 30s
        </p>
      )}
    </div>
  )
}

interface BalanceTileProps {
  label: string
  value: number
  loading?: boolean
  highlight?: boolean
  negative?: boolean
}

function BalanceTile({ label, value, loading, highlight, negative }: BalanceTileProps) {
  return (
    <div className={`rounded-xl border p-4 ${highlight ? 'border-blue-200 bg-blue-50' : 'border-slate-200 bg-white'}`}>
      <p className="text-xs text-slate-500">{label}</p>
      {loading ? (
        <LoadingSpinner size="sm" className="mt-2" />
      ) : (
        <p className={`mt-1 text-xl font-bold ${negative ? 'text-red-600' : highlight ? 'text-blue-700' : 'text-slate-900'}`}>
          {formatCurrency(value)}
        </p>
      )}
    </div>
  )
}
