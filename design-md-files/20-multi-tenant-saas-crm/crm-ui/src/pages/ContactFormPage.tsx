import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { contactsApi } from '@/api/contacts'
import { Input } from '@/components/ui/Input'
import { Select } from '@/components/ui/Select'
import { Button } from '@/components/ui/Button'
import { PageSpinner } from '@/components/ui/Spinner'
import { extractErrorMessage } from '@/api/client'

const schema = z.object({
  firstName: z.string().min(1, 'First name is required'),
  lastName: z.string().optional(),
  email: z.string().email('Invalid email').optional().or(z.literal('')),
  phone: z.string().optional(),
  company: z.string().optional(),
  notes: z.string().optional(),
  leadStatus: z.enum(['NEW', 'CONTACTED', 'QUALIFIED', 'CONVERTED', 'LOST']).optional(),
})

type FormData = z.infer<typeof schema>

const STATUS_OPTIONS = [
  { label: 'New', value: 'NEW' },
  { label: 'Contacted', value: 'CONTACTED' },
  { label: 'Qualified', value: 'QUALIFIED' },
  { label: 'Converted', value: 'CONVERTED' },
  { label: 'Lost', value: 'LOST' },
]

export function ContactFormPage() {
  const { id } = useParams<{ id: string }>()
  const isEditing = !!id
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const { data: existing, isLoading } = useQuery({
    queryKey: ['contact', id],
    queryFn: () => contactsApi.get(id!),
    enabled: isEditing,
  })

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>({
    resolver: zodResolver(schema),
    defaultValues: { leadStatus: 'NEW' },
  })

  useEffect(() => {
    if (existing) {
      reset({
        firstName: existing.firstName,
        lastName: existing.lastName ?? '',
        email: existing.email ?? '',
        phone: existing.phone ?? '',
        company: existing.company ?? '',
        notes: existing.notes ?? '',
        leadStatus: existing.leadStatus,
      })
    }
  }, [existing, reset])

  const mutation = useMutation({
    mutationFn: (data: FormData) => {
      const req = {
        ...data,
        email: data.email || undefined,
      }
      return isEditing ? contactsApi.update(id!, req) : contactsApi.create(req)
    },
    onSuccess: () => {
      toast.success(isEditing ? 'Contact updated' : 'Contact created')
      queryClient.invalidateQueries({ queryKey: ['contacts'] })
      navigate('/contacts')
    },
    onError: (err) => toast.error(extractErrorMessage(err)),
  })

  if (isLoading) return <PageSpinner />

  return (
    <div className="p-8">
      <div className="mb-6">
        <Link to="/contacts" className="text-sm text-blue-600 hover:underline">
          ← Back to Contacts
        </Link>
        <h1 className="mt-2 text-2xl font-bold text-gray-900">
          {isEditing ? 'Edit Contact' : 'New Contact'}
        </h1>
      </div>

      <div className="max-w-xl rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
        <form onSubmit={handleSubmit((d) => mutation.mutate(d))} className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <Input
              label="First Name *"
              error={errors.firstName?.message}
              {...register('firstName')}
            />
            <Input
              label="Last Name"
              error={errors.lastName?.message}
              {...register('lastName')}
            />
          </div>
          <Input
            label="Email"
            type="email"
            error={errors.email?.message}
            {...register('email')}
          />
          <Input
            label="Phone"
            type="tel"
            {...register('phone')}
          />
          <Input
            label="Company"
            {...register('company')}
          />
          <Select
            label="Lead Status"
            options={STATUS_OPTIONS}
            {...register('leadStatus')}
          />
          <div className="flex flex-col gap-1">
            <label className="text-sm font-medium text-gray-700">Notes</label>
            <textarea
              className="block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
              rows={3}
              {...register('notes')}
            />
          </div>

          <div className="flex justify-end gap-3 pt-2">
            <Link to="/contacts">
              <Button variant="secondary">Cancel</Button>
            </Link>
            <Button type="submit" loading={mutation.isPending}>
              {isEditing ? 'Save Changes' : 'Create Contact'}
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}
