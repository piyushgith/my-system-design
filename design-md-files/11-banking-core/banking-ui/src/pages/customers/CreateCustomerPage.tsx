import { useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useCreateCustomer } from '../../hooks/useCustomer'
import { useToast } from '../../components/ui/ToastContext'
import { extractErrorMessage } from '../../api/axios'
import { Card, CardHeader } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { Input } from '../../components/ui/Input'
import { Select } from '../../components/ui/Select'
import { ErrorAlert } from '../../components/ui/ErrorAlert'
import { GENDER_OPTIONS } from '../../constants'

const schema = z.object({
  firstName: z.string().min(1, 'First name is required'),
  lastName: z.string().min(1, 'Last name is required'),
  dateOfBirth: z.string().min(1, 'Date of birth is required'),
  gender: z.string().optional(),
  pan: z
    .string()
    .min(1, 'PAN is required')
    .regex(/^[A-Z]{5}[0-9]{4}[A-Z]{1}$/, 'Invalid PAN format (e.g. ABCDE1234F)'),
  aadhaarToken: z.string().optional(),
})

type FormData = z.infer<typeof schema>

export function CreateCustomerPage() {
  const navigate = useNavigate()
  const { toast } = useToast()
  const mutation = useCreateCustomer()

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormData>({ resolver: zodResolver(schema) })

  const onSubmit = async (data: FormData) => {
    try {
      const customer = await mutation.mutateAsync(data)
      toast('success', `Customer ${customer.cifId} created successfully`)
      navigate(`/customers/${customer.cifId}`)
    } catch (err) {
      // error shown inline
    }
  }

  return (
    <div className="mx-auto max-w-xl">
      <div className="mb-6 flex items-center gap-4">
        <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>
          ← Back
        </Button>
        <h1 className="text-2xl font-bold text-slate-900">New Customer</h1>
      </div>

      <Card>
        <CardHeader title="Customer KYC Details" subtitle="All fields marked * are required" />

        {mutation.error && (
          <div className="mb-4">
            <ErrorAlert message={extractErrorMessage(mutation.error)} />
          </div>
        )}

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <Input
              label="First Name"
              required
              {...register('firstName')}
              error={errors.firstName?.message}
            />
            <Input
              label="Last Name"
              required
              {...register('lastName')}
              error={errors.lastName?.message}
            />
          </div>

          <Input
            label="Date of Birth"
            type="date"
            required
            {...register('dateOfBirth')}
            error={errors.dateOfBirth?.message}
          />

          <Select
            label="Gender"
            options={GENDER_OPTIONS}
            placeholder="Select gender"
            {...register('gender')}
            error={errors.gender?.message}
          />

          <Input
            label="PAN Number"
            required
            placeholder="ABCDE1234F"
            {...register('pan')}
            error={errors.pan?.message}
            hint="10-character PAN in format ABCDE1234F"
          />

          <Input
            label="Aadhaar Token"
            placeholder="Optional tokenized Aadhaar"
            {...register('aadhaarToken')}
            error={errors.aadhaarToken?.message}
          />

          <div className="flex gap-3 pt-2">
            <Button type="submit" loading={mutation.isPending} className="flex-1">
              Create Customer
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
