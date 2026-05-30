import { useState } from 'react'
import { useTransactionHistory } from '../../../hooks/useTransaction'
import { Card, CardHeader } from '../../../components/ui/Card'
import { Button } from '../../../components/ui/Button'
import { Input } from '../../../components/ui/Input'
import { LoadingSpinner } from '../../../components/ui/LoadingSpinner'
import { formatCurrency } from '../../../utils/currency'
import { DEFAULT_PAGE_SIZE } from '../../../constants'

interface Props {
  accountId: string
}

export function TransactionsTab({ accountId }: Props) {
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [page, setPage] = useState(0)
  const [appliedFilters, setAppliedFilters] = useState<{
    fromDate?: string
    toDate?: string
  }>({})

  const { data, isLoading, isError } = useTransactionHistory(accountId, {
    ...appliedFilters,
    page,
    size: DEFAULT_PAGE_SIZE,
  })

  const handleApply = () => {
    setPage(0)
    setAppliedFilters({
      fromDate: fromDate || undefined,
      toDate: toDate || undefined,
    })
  }

  const handleClear = () => {
    setFromDate('')
    setToDate('')
    setPage(0)
    setAppliedFilters({})
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader title="Date Filter" />
        <div className="flex flex-wrap gap-3 items-end">
          <div className="flex-1 min-w-32">
            <Input
              label="From"
              type="date"
              value={fromDate}
              onChange={(e) => setFromDate(e.target.value)}
            />
          </div>
          <div className="flex-1 min-w-32">
            <Input
              label="To"
              type="date"
              value={toDate}
              onChange={(e) => setToDate(e.target.value)}
            />
          </div>
          <div className="flex gap-2 pb-0.5">
            <Button size="sm" onClick={handleApply}>Apply</Button>
            <Button size="sm" variant="ghost" onClick={handleClear}>Clear</Button>
          </div>
        </div>
      </Card>

      <Card padding="none">
        {isLoading && (
          <div className="flex justify-center py-10">
            <LoadingSpinner size="md" />
          </div>
        )}

        {isError && (
          <p className="p-4 text-sm text-red-600">Failed to load transactions.</p>
        )}

        {data && (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="border-b border-slate-200 bg-slate-50">
                  <tr>
                    <th className="px-4 py-3 text-left font-medium text-slate-600">Date</th>
                    <th className="px-4 py-3 text-left font-medium text-slate-600">Txn ID</th>
                    <th className="px-4 py-3 text-left font-medium text-slate-600">Type</th>
                    <th className="px-4 py-3 text-left font-medium text-slate-600">Narration</th>
                    <th className="px-4 py-3 text-right font-medium text-slate-600">Amount</th>
                    <th className="px-4 py-3 text-right font-medium text-slate-600">Balance</th>
                  </tr>
                </thead>
                <tbody>
                  {data.transactions.length === 0 && (
                    <tr>
                      <td colSpan={6} className="px-4 py-8 text-center text-slate-400">
                        No transactions found.
                      </td>
                    </tr>
                  )}
                  {data.transactions.map((txn) => (
                    <tr key={txn.txnId} className="border-b border-slate-100 hover:bg-slate-50">
                      <td className="px-4 py-3 text-slate-600 whitespace-nowrap">{txn.postingDate}</td>
                      <td className="px-4 py-3 font-mono text-xs text-slate-500">{txn.txnId}</td>
                      <td className="px-4 py-3">
                        <span
                          className={`inline-block rounded px-1.5 py-0.5 text-xs font-medium ${
                            txn.type === 'CREDIT'
                              ? 'bg-green-100 text-green-700'
                              : 'bg-red-100 text-red-700'
                          }`}
                        >
                          {txn.type}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-slate-600 max-w-xs truncate">{txn.narration ?? '—'}</td>
                      <td
                        className={`px-4 py-3 text-right font-medium tabular-nums ${
                          txn.type === 'CREDIT' ? 'text-green-700' : 'text-red-600'
                        }`}
                      >
                        {txn.type === 'DEBIT' ? '−' : '+'}{formatCurrency(txn.amount)}
                      </td>
                      <td className="px-4 py-3 text-right font-medium tabular-nums text-slate-900">
                        {formatCurrency(txn.runningBalance)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {data.pagination.totalPages > 1 && (
              <div className="flex items-center justify-between border-t border-slate-200 px-4 py-3 text-sm text-slate-600">
                <span>
                  Page {data.pagination.page + 1} of {data.pagination.totalPages} ·{' '}
                  {data.pagination.totalElements} transactions
                </span>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={page === 0}
                    onClick={() => setPage((p) => p - 1)}
                  >
                    Previous
                  </Button>
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={!data.pagination.hasNext}
                    onClick={() => setPage((p) => p + 1)}
                  >
                    Next
                  </Button>
                </div>
              </div>
            )}
          </>
        )}
      </Card>
    </div>
  )
}
