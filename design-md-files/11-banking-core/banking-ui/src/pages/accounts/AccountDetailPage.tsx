import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useAccount } from '../../hooks/useAccount'
import { useAuthStore } from '../../store/authStore'
import { Button } from '../../components/ui/Button'
import { Badge } from '../../components/ui/Badge'
import { LoadingSpinner } from '../../components/ui/LoadingSpinner'
import { ErrorAlert } from '../../components/ui/ErrorAlert'
import { OverviewTab } from './tabs/OverviewTab'
import { TransactionsTab } from './tabs/TransactionsTab'
import { StatementTab } from './tabs/StatementTab'
import { LiensTab } from './tabs/LiensTab'

type Tab = 'overview' | 'transactions' | 'statement' | 'liens'

const TABS: { id: Tab; label: string; tellerOnly?: boolean }[] = [
  { id: 'overview', label: 'Overview' },
  { id: 'transactions', label: 'Transactions' },
  { id: 'statement', label: 'Statement' },
  { id: 'liens', label: 'Liens', tellerOnly: true },
]

export function AccountDetailPage() {
  const { accountId } = useParams<{ accountId: string }>()
  const navigate = useNavigate()
  const { role } = useAuthStore()
  const isTeller = role === 'TELLER'

  const [activeTab, setActiveTab] = useState<Tab>('overview')

  const { data: account, isLoading, isError, error } = useAccount(accountId)

  const visibleTabs = TABS.filter((t) => !t.tellerOnly || isTeller)

  if (isLoading) {
    return (
      <div className="flex justify-center py-16">
        <LoadingSpinner size="lg" />
      </div>
    )
  }

  if (isError || !account) {
    return (
      <div className="mx-auto max-w-xl space-y-4">
        <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>← Back</Button>
        <ErrorAlert message={error instanceof Error ? error.message : 'Account not found or access denied.'} />
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div className="flex items-center gap-4">
          <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>← Back</Button>
          <div>
            <h1 className="text-2xl font-bold text-slate-900 font-mono">{account.accountId}</h1>
            <p className="text-sm text-slate-500 mt-0.5">
              {account.accountType} · {account.cifId}
            </p>
          </div>
        </div>
        <div className="flex gap-2">
          <Badge>{account.status}</Badge>
          <Badge>{account.kycStatus}</Badge>
        </div>
      </div>

      {/* Tab bar */}
      <div className="border-b border-slate-200">
        <nav className="-mb-px flex gap-1">
          {visibleTabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
                activeTab === tab.id
                  ? 'border-blue-600 text-blue-600'
                  : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </nav>
      </div>

      {/* Tab content */}
      {activeTab === 'overview' && <OverviewTab account={account} />}
      {activeTab === 'transactions' && <TransactionsTab accountId={account.accountId} />}
      {activeTab === 'statement' && <StatementTab accountId={account.accountId} />}
      {activeTab === 'liens' && isTeller && <LiensTab accountId={account.accountId} />}
    </div>
  )
}
