import { useEffect } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useOpenAccount } from '../../hooks/useAccount'
import { useToast } from '../../components/ui/ToastContext'
import { extractErrorMessage } from '../../api/axios'
import { Card, CardHeader } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { Input } from '../../components/ui/Input'
import { Select } from '../../components/ui/Select'
import { ErrorAlert } from '../../components/ui/ErrorAlert'
import { ACCOUNT_TYPES } from '../../constants'

const schema = z.object({
  cifId: z.string().min(1, 'CIF ID is required'),
  accountType: z.string().min(1, 'Account type is required'),
  productCode: z.string().optional(),
  initialDeposit: z.string().optional(),
})

type FormData = z.infer<typeof schema>

export function OpenAccountPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { toast } = useToast()
  const mutation = useOpenAccount()

  const prefillCifId = (location.state as { cifId?: string } | null)?.cifId ?? ''

  const {
    register,
    handleSubmit,
    setValue,
    formState: { errors },
  } = useForm<FormData>({ resolver: zodResolver(schema) })

  useEffect(() => {
    if (prefillCifId) setValue('cifId', prefillCifId)
  }, [prefillCifId, setValue])

  const onSubmit = async (data: FormData) => {
    try {
      const initialDeposit = data.initialDeposit ? Number.parseFloat(data.initialDeposit) : undefined
      if (initialDeposit !== undefined && (Number.isNaN(initialDeposit) || initialDeposit < 0)) {
        return
      }
      const account = await mutation.mutateAsync({
        cifId: data.cifId,
        accountType: data.accountType,
        productCode: data.productCode,
        initialDeposit,
      })
      toast('success', `Account ${account.accountId} opened successfully`)
      navigate(`/accounts/${account.accountId}`)
    } catch {
      // error shown inline
    }
  }

  return (
    <div className="mx-auto max-w-xl">
      <div className="mb-6 flex items-center gap-4">
        <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>← Back</Button>
        <h1 className="text-2xl font-bold text-slate-900">Open Account</h1>
      </div>

      <Card>
        <CardHeader title="Account Details" subtitle="Fields marked * are required" />

        {mutation.error && (
          <div className="mb-4">
            <ErrorAlert message={extractErrorMessage(mutation.error)} />
          </div>
        )}

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <Input
            label="CIF ID"
            required
            placeholder="CIF-10000001"
            {...register('cifId')}
            error={errors.cifId?.message}
          />

          <Select
            label="Account Type"
            required
            options={ACCOUNT_TYPES}
            placeholder="Select account type"
            {...register('accountType')}
            error={errors.accountType?.message}
          />

          <Input
            label="Product Code"
            placeholder="e.g. SB-BASIC"
            {...register('productCode')}
            error={errors.productCode?.message}
            hint="Optional product code assigned by the bank"
          />

          <Input
            label="Initial Deposit (INR)"
            type="number"
            min="0"
            step="0.01"
            placeholder="0.00"
            {...register('initialDeposit')}
            error={errors.initialDeposit?.message}
            hint="Leave blank to open with zero balance"
          />

          <div className="flex gap-3 pt-2">
            <Button type="submit" loading={mutation.isPending} className="flex-1">
              Open Account
            </Button>
            <Button type="button" variant="secondary" onClick={() => navigate(-1)}>
              Cancel
            </Button>
          </div>
        </form>
      </Card>
    </div>
  )
}
