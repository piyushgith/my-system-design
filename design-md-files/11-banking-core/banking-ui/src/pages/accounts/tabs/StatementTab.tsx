import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useStatement } from '../../../hooks/useTransaction'
import { Card, CardHeader } from '../../../components/ui/Card'
import { Button } from '../../../components/ui/Button'
import { Input } from '../../../components/ui/Input'
import { LoadingSpinner } from '../../../components/ui/LoadingSpinner'
import { formatCurrency } from '../../../utils/currency'
import type { StatementRequest } from '../../../types'

interface Props {
  accountId: string
}

const schema = z
  .object({
    fromDate: z.string().min(1, 'From date is required'),
    toDate: z.string().min(1, 'To date is required'),
  })
  .refine((d) => d.fromDate <= d.toDate, {
    message: 'From date must be before or equal to To date',
    path: ['toDate'],
  })

type FormData = z.infer<typeof schema>

export function StatementTab({ accountId }: Props) {
  const [request, setRequest] = useState<StatementRequest | null>(null)
  const { data, isLoading, isError } = useStatement(accountId, request)

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormData>({ resolver: zodResolver(schema) })

  const onSubmit = (data: FormData) => {
    setRequest({ fromDate: data.fromDate, toDate: data.toDate })
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader title="Request Statement" subtitle="Select a date range to generate the statement" />
        <form onSubmit={handleSubmit(onSubmit)} className="flex flex-wrap gap-3 items-end">
          <div className="flex-1 min-w-32">
            <Input
              label="From Date"
              type="date"
              required
              {...register('fromDate')}
              error={errors.fromDate?.message}
            />
          </div>
          <div className="flex-1 min-w-32">
            <Input
              label="To Date"
              type="date"
              required
              {...register('toDate')}
              error={errors.toDate?.message}
            />
          </div>
          <div className="pb-0.5">
            <Button type="submit" loading={isLoading}>Generate</Button>
          </div>
        </form>
      </Card>

      {isLoading && (
        <div className="flex justify-center py-10">
          <LoadingSpinner size="md" />
        </div>
      )}

      {isError && (
        <Card>
          <p className="text-sm text-red-600">Failed to load statement.</p>
        </Card>
      )}

      {data && (
        <Card padding="none">
          <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
            <div>
              <p className="font-medium text-slate-900">Statement: {data.fromDate} → {data.toDate}</p>
              <p className="text-xs text-slate-500 mt-0.5">{data.accountId}</p>
            </div>
            <span className="text-xs text-slate-400">{data.lines.length} entries</span>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="border-b border-slate-200 bg-slate-50">
                <tr>
                  <th className="px-4 py-3 text-left font-medium text-slate-600">Date</th>
                  <th className="px-4 py-3 text-left font-medium text-slate-600">Txn ID</th>
                  <th className="px-4 py-3 text-left font-medium text-slate-600">Type</th>
                  <th className="px-4 py-3 text-left font-medium text-slate-600">Narration</th>
                  <th className="px-4 py-3 text-right font-medium text-slate-600">Amount</th>
                </tr>
              </thead>
              <tbody>
                {data.lines.length === 0 && (
                  <tr>
                    <td colSpan={5} className="px-4 py-8 text-center text-slate-400">
                      No entries in this period.
                    </td>
                  </tr>
                )}
                {data.lines.map((line) => (
                  <tr key={line.txnId} className="border-b border-slate-100 hover:bg-slate-50">
                    <td className="px-4 py-3 text-slate-600 whitespace-nowrap">{line.postingDate}</td>
                    <td className="px-4 py-3 font-mono text-xs text-slate-500">{line.txnId}</td>
                    <td className="px-4 py-3">
                      <span
                        className={`inline-block rounded px-1.5 py-0.5 text-xs font-medium ${
                          line.entryType === 'CREDIT'
                            ? 'bg-green-100 text-green-700'
                            : 'bg-red-100 text-red-700'
                        }`}
                      >
                        {line.entryType}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-slate-600 max-w-xs truncate">{line.narration ?? '—'}</td>
                    <td
                      className={`px-4 py-3 text-right font-medium tabular-nums ${
                        line.entryType === 'CREDIT' ? 'text-green-700' : 'text-red-600'
                      }`}
                    >
                      {line.entryType === 'DEBIT' ? '−' : '+'}{formatCurrency(line.amount)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </div>
  )
}
