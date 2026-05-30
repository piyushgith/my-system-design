import { useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useDeposit } from '../../hooks/useTransaction'
import { useToast } from '../../components/ui/ToastContext'
import { extractErrorMessage } from '../../api/axios'
import { Card, CardHeader } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { Input } from '../../components/ui/Input'
import { ErrorAlert } from '../../components/ui/ErrorAlert'
import { formatCurrency } from '../../utils/currency'

const schema = z.object({
  accountId: z.string().min(1, 'Account ID is required'),
  amount: z.string().min(1, 'Amount is required'),
  narration: z.string().optional(),
  referenceNumber: z.string().optional(),
  remitterIfsc: z
    .string()
    .optional()
    .refine((v) => !v || /^[A-Z]{4}0[A-Z0-9]{6}$/.test(v), 'Invalid IFSC format'),
  valueDate: z.string().optional(),
})

type FormData = z.infer<typeof schema>

export function DepositPage() {
  const navigate = useNavigate()
  const { toast } = useToast()
  const mutation = useDeposit()

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>({ resolver: zodResolver(schema) })

  const onSubmit = async (data: FormData) => {
    try {
      const result = await mutation.mutateAsync({
        accountId: data.accountId,
        amount: Number.parseFloat(data.amount),
        narration: data.narration,
        referenceNumber: data.referenceNumber,
        remitterIfsc: data.remitterIfsc,
        valueDate: data.valueDate,
      })
      toast(
        'success',
        `Deposited ${formatCurrency(result.accountBalance)} · Txn ${result.txnId}`
      )
      reset()
    } catch {
      // error shown inline
    }
  }

  return (
    <div className="mx-auto max-w-xl">
      <div className="mb-6 flex items-center gap-4">
        <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>← Back</Button>
        <h1 className="text-2xl font-bold text-slate-900">Deposit</h1>
      </div>

      <Card>
        <CardHeader title="Post Deposit" subtitle="Cash or NEFT credit to account" />

        {mutation.error && (
          <div className="mb-4">
            <ErrorAlert message={extractErrorMessage(mutation.error)} />
          </div>
        )}

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <Input
            label="Account ID"
            required
            placeholder="ACC-10000001"
            {...register('accountId')}
            error={errors.accountId?.message}
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
            placeholder="e.g. Cash Deposit"
            {...register('narration')}
            error={errors.narration?.message}
          />

          <Input
            label="Reference Number"
            placeholder="UTR / NEFT reference"
            {...register('referenceNumber')}
            error={errors.referenceNumber?.message}
          />

          <Input
            label="Remitter IFSC"
            placeholder="e.g. HDFC0001234"
            {...register('remitterIfsc')}
            error={errors.remitterIfsc?.message}
            hint="Required for NEFT / RTGS inward credits"
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
              Post Deposit
            </Button>
            <Button type="button" variant="secondary" onClick={() => reset()}>
              Clear
            </Button>
          </div>
        </form>
      </Card>

      {mutation.data && (
        <Card className="mt-4 border-green-200 bg-green-50">
          <p className="text-sm font-medium text-green-800">Deposit posted successfully</p>
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
              <dt className="text-green-600">New Balance</dt>
              <dd className="font-medium text-green-900">{formatCurrency(mutation.data.accountBalance)}</dd>
            </div>
            <div>
              <dt className="text-green-600">Posting Date</dt>
              <dd className="font-medium text-green-900">{mutation.data.postingDate}</dd>
            </div>
          </dl>
        </Card>
      )}
    </div>
  )
}
