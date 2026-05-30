import { useQuery } from '@tanstack/react-query'
import { dashboardApi } from '@/api/dashboard'
import { PageSpinner } from '@/components/ui/Spinner'

interface StatCardProps {
  title: string
  value: string | number
  color?: string
}

function StatCard({ title, value, color = 'text-gray-900' }: StatCardProps) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
      <p className="text-sm font-medium text-gray-500">{title}</p>
      <p className={`mt-2 text-3xl font-bold ${color}`}>{value}</p>
    </div>
  )
}

function formatCurrency(value: number): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(value)
}

export function DashboardPage() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['dashboard'],
    queryFn: dashboardApi.getSummary,
    refetchInterval: 60_000,
  })

  if (isLoading) return <PageSpinner />

  if (isError) {
    return (
      <div className="p-8">
        <p className="text-red-500">Failed to load dashboard. Please refresh.</p>
      </div>
    )
  }

  if (!data) return null

  return (
    <div className="p-8">
      <div className="mb-8">
        <h1 className="text-2xl font-bold text-gray-900">Dashboard</h1>
        <p className="mt-1 text-sm text-gray-500">Overview of your CRM activity</p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <StatCard title="Total Contacts" value={data.totalContacts} />
        <StatCard title="Open Leads" value={data.openLeads} color="text-blue-600" />
        <StatCard title="Open Deals" value={data.openDeals} color="text-blue-600" />
        <StatCard title="Deals Won" value={data.wonDeals} color="text-green-600" />
        <StatCard title="Deals Lost" value={data.lostDeals} color="text-red-500" />
        <StatCard
          title="Open Pipeline Value"
          value={formatCurrency(data.openPipelineValue)}
          color="text-purple-600"
        />
      </div>
    </div>
  )
}
