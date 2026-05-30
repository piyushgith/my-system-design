import { useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useTransfer } from '../../hooks/useTransaction'
import { useToast } from '../../components/ui/ToastContext'
import { extractErrorMessage } from '../../api/axios'
import { Card, CardHeader } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { Input } from '../../components/ui/Input'
import { ErrorAlert } from '../../components/ui/ErrorAlert'
import { formatCurrency } from '../../utils/currency'

const schema = z
  .object({
    fromAccountId: z.string().min(1, 'Source account is required'),
    toAccountId: z.string().min(1, 'Destination account is required'),
    amount: z.string().min(1, 'Amount is required'),
    narration: z.string().optional(),
    valueDate: z.string().optional(),
  })
  .refine((d) => d.fromAccountId !== d.toAccountId, {
    message: 'Source and destination accounts must differ',
    path: ['toAccountId'],
  })

type FormData = z.infer<typeof schema>

export function TransferPage() {
  const navigate = useNavigate()
  const { toast } = useToast()
  const mutation = useTransfer()

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>({ resolver: zodResolver(schema) })

  const onSubmit = async (data: FormData) => {
    try {
      const result = await mutation.mutateAsync({
        fromAccountId: data.fromAccountId,
        toAccountId: data.toAccountId,
        amount: Number.parseFloat(data.amount),
        narration: data.narration,
        valueDate: data.valueDate,
      })
      toast('success', `Transfer complete · Txn ${result.txnId}`)
      reset()
    } catch {
      // error shown inline
    }
  }

  return (
    <div className="mx-auto max-w-xl">
      <div className="mb-6 flex items-center gap-4">
        <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>← Back</Button>
        <h1 className="text-2xl font-bold text-slate-900">Fund Transfer</h1>
      </div>

      <Card>
        <CardHeader title="Internal Transfer" subtitle="Move funds between two accounts" />

        {mutation.error && (
          <div className="mb-4">
            <ErrorAlert message={extractErrorMessage(mutation.error)} />
          </div>
        )}

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <Input
            label="From Account"
            required
            placeholder="ACC-10000001"
            {...register('fromAccountId')}
            error={errors.fromAccountId?.message}
          />

          <Input
            label="To Account"
            required
            placeholder="ACC-10000002"
            {...register('toAccountId')}
            error={errors.toAccountId?.message}
          />

          <Input
            label="Amount (INR)"
            type="number"
            min="0.01"
            step="0.01"
            required
            placeholder="0.00"
            {...register('amount')}
            error={errors.amount?.message}
          />

          <Input
            label="Narration"
            placeholder="e.g. Rent payment"
            {...register('narration')}
            error={errors.narration?.message}
          />

          <Input
            label="Value Date"
            type="date"
            {...register('valueDate')}
            error={errors.valueDate?.message}
            hint="Defaults to today if not set"
          />

          <div className="flex gap-3 pt-2">
            <Button type="submit" loading={mutation.isPending} className="flex-1">
              Transfer Funds
            </Button>
            <Button type="button" variant="secondary" onClick={() => reset()}>
              Clear
            </Button>
          </div>
        </form>
      </Card>

      {mutation.data && (
        <Card className="mt-4 border-green-200 bg-green-50">
          <p className="text-sm font-medium text-green-800">Transfer posted successfully</p>
          <dl className="mt-3 grid grid-cols-2 gap-2 text-sm">
            <div>
              <dt className="text-green-600">Transaction ID</dt>
              <dd className="font-mono font-medium text-green-900">{mutation.data.txnId}</dd>
            </div>
            <div>
              <dt className="text-green-600">Status</dt>
              <dd className="font-medium text-green-900">{mutation.data.status}</dd>
            </div>
            <div>
              <dt className="text-green-600">From Balance</dt>
              <dd className="font-medium text-green-900">{formatCurrency(mutation.data.fromAccountBalance)}</dd>
            </div>
            <div>
              <dt className="text-green-600">To Balance</dt>
              <dd className="font-medium text-green-900">{formatCurrency(mutation.data.toAccountBalance)}</dd>
            </div>
            <div className="col-span-2">
              <dt className="text-green-600">Posting Date</dt>
              <dd className="font-medium text-green-900">{mutation.data.postingDate}</dd>
            </div>
          </dl>
        </Card>
      )}
    </div>
  )
}
