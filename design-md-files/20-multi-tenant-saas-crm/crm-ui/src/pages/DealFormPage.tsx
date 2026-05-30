import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { dealsApi } from '@/api/deals'
import { pipelinesApi } from '@/api/pipelines'
import { contactsApi } from '@/api/contacts'
import { Input } from '@/components/ui/Input'
import { Select } from '@/components/ui/Select'
import { Button } from '@/components/ui/Button'
import { PageSpinner } from '@/components/ui/Spinner'
import { extractErrorMessage } from '@/api/client'
import { useAuthStore } from '@/store/authStore'

const schema = z.object({
  title: z.string().min(1, 'Title is required'),
  value: z.coerce.number().positive('Must be positive').optional().or(z.literal('')),
  currency: z.string().optional(),
  pipelineId: z.string().min(1, 'Pipeline is required'),
  stageId: z.string().min(1, 'Stage is required'),
  contactId: z.string().optional(),
  ownerId: z.string().min(1, 'Owner is required'),
  expectedCloseDate: z.string().optional(),
  status: z.enum(['OPEN', 'WON', 'LOST']).optional(),
})

type FormData = z.infer<typeof schema>

const DEAL_STATUS_OPTIONS = [
  { label: 'Open', value: 'OPEN' },
  { label: 'Won', value: 'WON' },
  { label: 'Lost', value: 'LOST' },
]

export function DealFormPage() {
  const { id } = useParams<{ id: string }>()
  const isEditing = !!id
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { user } = useAuthStore()

  const [selectedPipelineId, setSelectedPipelineId] = useState<string>('')

  const { data: existing, isLoading: isLoadingDeal } = useQuery({
    queryKey: ['deal', id],
    queryFn: () => dealsApi.get(id!),
    enabled: isEditing,
  })

  const { data: pipelines } = useQuery({
    queryKey: ['pipelines'],
    queryFn: pipelinesApi.list,
  })

  const { data: stages } = useQuery({
    queryKey: ['stages', selectedPipelineId],
    queryFn: () => pipelinesApi.listStages(selectedPipelineId),
    enabled: !!selectedPipelineId,
  })

  const { data: contacts } = useQuery({
    queryKey: ['contacts', { pageSize: 100 }],
    queryFn: () => contactsApi.list({ pageSize: 100 }),
  })

  const {
    register,
    handleSubmit,
    reset,
    watch,
    setValue,
    formState: { errors },
  } = useForm<FormData>({
    resolver: zodResolver(schema),
    defaultValues: {
      status: 'OPEN',
      ownerId: user?.userId ?? '',
    },
  })

  const watchedPipeline = watch('pipelineId')

  useEffect(() => {
    if (watchedPipeline && watchedPipeline !== selectedPipelineId) {
      setSelectedPipelineId(watchedPipeline)
      setValue('stageId', '')
    }
  }, [watchedPipeline, selectedPipelineId, setValue])

  useEffect(() => {
    if (existing) {
      setSelectedPipelineId(existing.pipelineId)
      reset({
        title: existing.title,
        value: existing.value ?? undefined,
        currency: existing.currency ?? 'USD',
        pipelineId: existing.pipelineId,
        stageId: existing.stageId,
        contactId: existing.contactId ?? '',
        ownerId: existing.ownerId,
        expectedCloseDate: existing.expectedCloseDate ?? '',
        status: existing.status,
      })
    }
  }, [existing, reset])

  const mutation = useMutation({
    mutationFn: (data: FormData) => {
      const req = {
        title: data.title,
        value: data.value !== '' ? Number(data.value) : undefined,
        currency: data.currency || 'USD',
        pipelineId: data.pipelineId,
        stageId: data.stageId,
        contactId: data.contactId || undefined,
        ownerId: data.ownerId,
        expectedCloseDate: data.expectedCloseDate || undefined,
        status: data.status,
      }
      return isEditing ? dealsApi.update(id!, req) : dealsApi.create(req)
    },
    onSuccess: () => {
      toast.success(isEditing ? 'Deal updated' : 'Deal created')
      queryClient.invalidateQueries({ queryKey: ['deals'] })
      navigate('/deals')
    },
    onError: (err) => toast.error(extractErrorMessage(err)),
  })

  if (isLoadingDeal) return <PageSpinner />

  const pipelineOptions = pipelines?.map((p) => ({ label: p.name, value: p.pipelineId })) ?? []
  const stageOptions = stages?.map((s) => ({ label: `${s.name} (${s.probability}%)`, value: s.stageId })) ?? []
  const contactOptions = [
    { label: 'No contact', value: '' },
    ...(contacts?.data?.map((c) => ({
      label: `${c.firstName} ${c.lastName ?? ''}`.trim(),
      value: c.contactId,
    })) ?? []),
  ]

  return (
    <div className="p-8">
      <div className="mb-6">
        <Link to="/deals" className="text-sm text-blue-600 hover:underline">
          ← Back to Deals
        </Link>
        <h1 className="mt-2 text-2xl font-bold text-gray-900">
          {isEditing ? 'Edit Deal' : 'New Deal'}
        </h1>
      </div>

      <div className="max-w-xl rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
        <form onSubmit={handleSubmit((d) => mutation.mutate(d))} className="space-y-4">
          <Input
            label="Title *"
            error={errors.title?.message}
            {...register('title')}
          />

          <div className="grid grid-cols-2 gap-4">
            <Input
              label="Value"
              type="number"
              min="0"
              step="0.01"
              error={errors.value?.message as string | undefined}
              {...register('value')}
            />
            <Input
              label="Currency"
              placeholder="USD"
              {...register('currency')}
            />
          </div>

          <Select
            label="Pipeline *"
            options={pipelineOptions}
            placeholder="Select pipeline"
            error={errors.pipelineId?.message}
            {...register('pipelineId')}
          />

          <Select
            label="Stage *"
            options={stageOptions}
            placeholder={selectedPipelineId ? 'Select stage' : 'Select pipeline first'}
            disabled={!selectedPipelineId}
            error={errors.stageId?.message}
            {...register('stageId')}
          />

          <Select
            label="Contact"
            options={contactOptions}
            {...register('contactId')}
          />

          <Input
            label="Owner ID *"
            error={errors.ownerId?.message}
            {...register('ownerId')}
          />

          <Input
            label="Expected Close Date"
            type="date"
            {...register('expectedCloseDate')}
          />

          <Select
            label="Status"
            options={DEAL_STATUS_OPTIONS}
            {...register('status')}
          />

          <div className="flex justify-end gap-3 pt-2">
            <Link to="/deals">
              <Button variant="secondary">Cancel</Button>
            </Link>
            <Button type="submit" loading={mutation.isPending}>
              {isEditing ? 'Save Changes' : 'Create Deal'}
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}
