import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '../store/authStore'
import { useAccount } from '../hooks/useAccount'
import { Card } from '../components/ui/Card'
import { Button } from '../components/ui/Button'
import { Badge } from '../components/ui/Badge'
import { Input } from '../components/ui/Input'
import { formatCurrency } from '../utils/currency'

export function DashboardPage() {
  const { role, username } = useAuthStore()
  const isTeller = role === 'TELLER'

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold text-slate-900">Dashboard</h1>
      {isTeller ? <TellerDashboard /> : <CustomerDashboard cifId={username!} />}
    </div>
  )
}

function TellerDashboard() {
  const navigate = useNavigate()
  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <QuickAction
          title="New Customer"
          desc="Register a new KYC customer"
          onClick={() => navigate('/customers/new')}
          color="blue"
        />
        <QuickAction
          title="Open Account"
          desc="Open savings or current account"
          onClick={() => navigate('/accounts/new')}
          color="green"
        />
        <QuickAction
          title="Deposit"
          desc="Post a cash or NEFT deposit"
          onClick={() => navigate('/transactions/deposit')}
          color="purple"
        />
        <QuickAction
          title="Transfer"
          desc="Internal fund transfer"
          onClick={() => navigate('/transactions/transfer')}
          color="orange"
        />
      </div>

      <AccountLookupCard />
    </div>
  )
}

function CustomerDashboard({ cifId }: { cifId: string }) {
  const navigate = useNavigate()
  return (
    <div className="space-y-6">
      <Card>
        <p className="text-sm text-slate-500">Logged in as</p>
        <p className="text-xl font-semibold text-slate-900">{cifId}</p>
        <p className="text-xs text-slate-400 mt-0.5">CUSTOMER</p>
      </Card>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <QuickAction
          title="Deposit"
          desc="Deposit funds into your account"
          onClick={() => navigate('/transactions/deposit')}
          color="green"
        />
        <QuickAction
          title="Transfer"
          desc="Transfer funds to another account"
          onClick={() => navigate('/transactions/transfer')}
          color="blue"
        />
        <QuickAction
          title="My Profile"
          desc="View your customer profile"
          onClick={() => navigate(`/customers/${cifId}`)}
          color="purple"
        />
      </div>

      <AccountLookupCard />
    </div>
  )
}

function AccountLookupCard() {
  const navigate = useNavigate()
  const [accountId, setAccountId] = useState('')
  const [lookupId, setLookupId] = useState<string | undefined>()
  const { data: account, isLoading, isError } = useAccount(lookupId)

  const handleLookup = (e: React.FormEvent) => {
    e.preventDefault()
    if (accountId.trim()) setLookupId(accountId.trim())
  }

  return (
    <Card>
      <h2 className="mb-4 text-base font-semibold text-slate-900">Account Lookup</h2>
      <form onSubmit={handleLookup} className="flex gap-3">
        <Input
          placeholder="Enter Account ID (e.g. ACC-10000001)"
          value={accountId}
          onChange={(e) => setAccountId(e.target.value)}
          className="flex-1"
        />
        <Button type="submit" variant="secondary" loading={isLoading}>
          Look Up
        </Button>
      </form>

      {isError && <p className="mt-3 text-sm text-red-600">Account not found or access denied.</p>}

      {account && (
        <div
          className="mt-4 cursor-pointer rounded-lg border border-slate-200 p-4 hover:border-blue-300 hover:bg-blue-50 transition-colors"
          onClick={() => navigate(`/accounts/${account.accountId}`)}
        >
          <div className="flex items-center justify-between">
            <div>
              <p className="font-semibold text-slate-900">{account.accountId}</p>
              <p className="text-xs text-slate-500">{account.accountType} · {account.cifId}</p>
            </div>
            <div className="text-right">
              <p className="font-semibold text-slate-900">{formatCurrency(account.availableBalance)}</p>
              <p className="text-xs text-slate-500">Available</p>
            </div>
          </div>
          <div className="mt-2 flex gap-2">
            <Badge>{account.status}</Badge>
            <Badge>{account.kycStatus}</Badge>
          </div>
        </div>
      )}
    </Card>
  )
}

interface QuickActionProps {
  title: string
  desc: string
  onClick: () => void
  color: 'blue' | 'green' | 'purple' | 'orange'
}

const colorMap = {
  blue: 'border-blue-200 hover:border-blue-400 hover:bg-blue-50',
  green: 'border-green-200 hover:border-green-400 hover:bg-green-50',
  purple: 'border-purple-200 hover:border-purple-400 hover:bg-purple-50',
  orange: 'border-orange-200 hover:border-orange-400 hover:bg-orange-50',
}

function QuickAction({ title, desc, onClick, color }: QuickActionProps) {
  return (
    <button
      onClick={onClick}
      className={`rounded-xl border-2 bg-white p-5 text-left transition-colors ${colorMap[color]}`}
    >
      <p className="font-semibold text-slate-900">{title}</p>
      <p className="mt-1 text-xs text-slate-500">{desc}</p>
    </button>
  )
}
