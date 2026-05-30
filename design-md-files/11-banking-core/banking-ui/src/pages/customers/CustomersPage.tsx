import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useCustomer } from '../../hooks/useCustomer'
import { Card, CardHeader } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { Input } from '../../components/ui/Input'
import { Badge } from '../../components/ui/Badge'
import { formatDate } from '../../utils/date'

export function CustomersPage() {
  const navigate = useNavigate()
  const [cifInput, setCifInput] = useState('')
  const [searchCif, setSearchCif] = useState<string | undefined>()
  const { data: customer, isLoading, isError } = useCustomer(searchCif)

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault()
    if (cifInput.trim()) setSearchCif(cifInput.trim())
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-slate-900">Customers</h1>
        <Button onClick={() => navigate('/customers/new')}>+ New Customer</Button>
      </div>

      <Card>
        <CardHeader title="Customer Lookup" subtitle="Search by CIF ID" />
        <form onSubmit={handleSearch} className="flex gap-3">
          <Input
            placeholder="CIF-10000001"
            value={cifInput}
            onChange={(e) => setCifInput(e.target.value)}
            className="flex-1"
          />
          <Button type="submit" variant="secondary" loading={isLoading}>
            Search
          </Button>
        </form>

        {isError && (
          <p className="mt-4 text-sm text-red-600">Customer not found.</p>
        )}

        {customer && (
          <div
            className="mt-4 cursor-pointer rounded-lg border border-slate-200 p-4 hover:border-blue-300 transition-colors"
            onClick={() => navigate(`/customers/${customer.cifId}`)}
          >
            <div className="flex items-start justify-between">
              <div>
                <p className="font-semibold text-slate-900">
                  {customer.firstName} {customer.lastName}
                </p>
                <p className="text-sm text-slate-500">{customer.cifId}</p>
                <p className="text-xs text-slate-400 mt-1">
                  DOB: {formatDate(customer.dateOfBirth)}
                </p>
              </div>
              <div className="flex flex-col items-end gap-1">
                <Badge>{customer.customerStatus}</Badge>
                <Badge>{customer.kycStatus}</Badge>
              </div>
            </div>
          </div>
        )}
      </Card>
    </div>
  )
}
