import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useLiens, usePlaceLien, useReleaseLien } from '../../../hooks/useLien'
import { useToast } from '../../../components/ui/ToastContext'
import { extractErrorMessage } from '../../../api/axios'
import { Card, CardHeader } from '../../../components/ui/Card'
import { Button } from '../../../components/ui/Button'
import { Input } from '../../../components/ui/Input'
import { Select } from '../../../components/ui/Select'
import { Badge } from '../../../components/ui/Badge'
import { LoadingSpinner } from '../../../components/ui/LoadingSpinner'
import { ErrorAlert } from '../../../components/ui/ErrorAlert'
import { formatCurrency } from '../../../utils/currency'
import { LIEN_TYPES } from '../../../constants'

interface Props {
  readonly accountId: string
}

const schema = z.object({
  amount: z.string().min(1, 'Amount is required'),
  reason: z.string().min(1, 'Reason is required'),
  lienType: z.string().optional(),
  referenceId: z.string().optional(),
})

type FormData = z.infer<typeof schema>

export function LiensTab({ accountId }: Props) {
  const { toast } = useToast()
  const { data: liens, isLoading } = useLiens(accountId)
  const placeMutation = usePlaceLien(accountId)
  const releaseMutation = useReleaseLien(accountId)

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>({ resolver: zodResolver(schema) })

  const onPlace = async (data: FormData) => {
    try {
      await placeMutation.mutateAsync({
        amount: Number.parseFloat(data.amount),
        reason: data.reason,
        lienType: data.lienType,
        referenceId: data.referenceId,
      })
      toast('success', 'Lien placed successfully')
      reset()
    } catch (err) {
      toast('error', extractErrorMessage(err))
    }
  }

  const handleRelease = async (lienId: string) => {
    try {
      await releaseMutation.mutateAsync(lienId)
      toast('success', 'Lien released')
    } catch (err) {
      toast('error', extractErrorMessage(err))
    }
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader title="Place New Lien" subtitle="TELLER action — holds funds in the account" />

        {placeMutation.error && (
          <div className="mb-4">
            <ErrorAlert message={extractErrorMessage(placeMutation.error)} />
          </div>
        )}

        <form onSubmit={handleSubmit(onPlace)} className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
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
            <Select
              label="Lien Type"
              options={LIEN_TYPES}
              placeholder="Select type"
              {...register('lienType')}
              error={errors.lienType?.message}
            />
          </div>

          <Input
            label="Reason"
            required
            placeholder="e.g. Court order — Case No. 123"
            {...register('reason')}
            error={errors.reason?.message}
          />

          <Input
            label="Reference ID"
            placeholder="External reference (optional)"
            {...register('referenceId')}
            error={errors.referenceId?.message}
          />

          <Button type="submit" loading={placeMutation.isPending}>
            Place Lien
          </Button>
        </form>
      </Card>

      <Card padding="none">
        <div className="border-b border-slate-200 px-4 py-3">
          <p className="font-medium text-slate-900">Active Liens</p>
        </div>

        {isLoading && (
          <div className="flex justify-center py-8">
            <LoadingSpinner size="md" />
          </div>
        )}

        {(liens?.length ?? 0) === 0 && !isLoading && (
          <p className="px-4 py-8 text-center text-sm text-slate-400">No liens on this account.</p>
        )}

        {(liens?.length ?? 0) > 0 && (
          <ul className="divide-y divide-slate-100">
            {liens?.map((lien) => (
              <li key={lien.lienId} className="flex items-start justify-between gap-4 px-4 py-4">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="font-semibold text-slate-900">{formatCurrency(lien.amount)}</span>
                    <Badge>{lien.status}</Badge>
                    {lien.lienType && <Badge>{lien.lienType}</Badge>}
                  </div>
                  <p className="mt-1 text-sm text-slate-600 truncate">{lien.reason}</p>
                  <p className="mt-0.5 font-mono text-xs text-slate-400">{lien.lienId}</p>
                  <p className="text-xs text-slate-400">{lien.createdAt}</p>
                </div>
                {lien.status === 'ACTIVE' && (
                  <Button
                    size="sm"
                    variant="secondary"
                    loading={releaseMutation.isPending}
                    onClick={() => handleRelease(lien.lienId)}
                  >
                    Release
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  )
}
