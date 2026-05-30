import { useForm, useFieldArray, Controller } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useNavigate } from 'react-router-dom'
import { useSubmitNotification } from '../hooks/useNotifications'
import { useToast } from '../components/ui/Toast'
import { Input } from '../components/ui/Input'
import { Select } from '../components/ui/Select'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { extractErrorMessage } from '../utils/errors'
import { ALL_CHANNELS, ALL_CATEGORIES, ALL_PRIORITIES, CHANNEL_LABELS, CATEGORY_LABELS, PRIORITY_LABELS } from '../constants'
import type { Channel } from '../types'

const schema = z.object({
  recipientUserId: z.string().uuid('Must be a valid UUID'),
  templateId: z.string().min(1, 'Template ID is required'),
  templateVersion: z.string().optional(),
  category: z.enum(['TRANSACTIONAL', 'MARKETING', 'PRODUCT_UPDATE', 'SECURITY']),
  priority: z.enum(['CRITICAL', 'HIGH', 'NORMAL', 'LOW']),
  channelsOverride: z.array(z.enum(['EMAIL', 'SMS', 'PUSH', 'IN_APP'])).optional(),
  scheduledAt: z.string().optional(),
  expiresAt: z.string().min(1, 'Expiry is required'),
  producerService: z.string().min(1, 'Producer service is required'),
  producerTraceId: z.string().min(1, 'Trace ID is required'),
  variables: z.array(z.object({ key: z.string().min(1), value: z.string() })).optional(),
})

type FormValues = z.infer<typeof schema>

export function SendNotificationPage() {
  const navigate = useNavigate()
  const { addToast } = useToast()
  const { mutateAsync, isPending } = useSubmitNotification()

  const {
    register,
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      category: 'TRANSACTIONAL',
      priority: 'NORMAL',
      producerService: 'notification-ui',
      producerTraceId: crypto.randomUUID(),
      variables: [{ key: '', value: '' }],
    },
  })

  const { fields, append, remove } = useFieldArray({ control, name: 'variables' })

  async function onSubmit(values: FormValues) {
    const variables: Record<string, string> = {}
    for (const v of values.variables ?? []) {
      if (v.key) variables[v.key] = v.value
    }

    try {
      const result = await mutateAsync({
        idempotencyKey: crypto.randomUUID(),
        body: {
          recipientUserId: values.recipientUserId,
          templateId: values.templateId,
          templateVersion: values.templateVersion ? parseInt(values.templateVersion) : undefined,
          category: values.category,
          priority: values.priority,
          channelsOverride: values.channelsOverride?.length ? values.channelsOverride : undefined,
          scheduledAt: values.scheduledAt || undefined,
          expiresAt: values.expiresAt,
          variables,
          producerContext: {
            service: values.producerService,
            traceId: values.producerTraceId,
          },
        },
      })
      addToast('success', `Notification submitted — ID: ${result.notificationId}`)
      navigate(`/notifications/${result.notificationId}`)
    } catch (err) {
      addToast('error', extractErrorMessage(err))
    }
  }

  return (
    <div className="space-y-6 max-w-2xl">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Send Notification</h1>
        <p className="mt-1 text-sm text-gray-500">
          Submit a notification request to the backend. Status 202 = accepted for async processing.
        </p>
      </div>

      <form onSubmit={handleSubmit(onSubmit)} className="space-y-5">
        <Card title="Recipient & Template">
          <div className="space-y-4">
            <Input
              label="Recipient User ID"
              placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
              required
              error={errors.recipientUserId?.message}
              {...register('recipientUserId')}
            />
            <div className="grid grid-cols-2 gap-4">
              <Input
                label="Template ID"
                placeholder="welcome-email"
                required
                error={errors.templateId?.message}
                {...register('templateId')}
              />
              <Input
                label="Template Version"
                placeholder="1 (leave blank for latest)"
                error={errors.templateVersion?.message}
                {...register('templateVersion')}
              />
            </div>
          </div>
        </Card>

        <Card title="Routing">
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <Select
                label="Category"
                required
                error={errors.category?.message}
                options={ALL_CATEGORIES.map((c) => ({ value: c, label: CATEGORY_LABELS[c] }))}
                {...register('category')}
              />
              <Select
                label="Priority"
                required
                error={errors.priority?.message}
                options={ALL_PRIORITIES.map((p) => ({ value: p, label: PRIORITY_LABELS[p] }))}
                {...register('priority')}
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">
                Channel Override{' '}
                <span className="text-gray-400 font-normal">(leave blank to use user preferences)</span>
              </label>
              <Controller
                control={control}
                name="channelsOverride"
                render={({ field }) => (
                  <div className="flex flex-wrap gap-2">
                    {ALL_CHANNELS.map((ch) => {
                      const checked = field.value?.includes(ch) ?? false
                      return (
                        <label key={ch} className="flex items-center gap-1.5 cursor-pointer">
                          <input
                            type="checkbox"
                            checked={checked}
                            onChange={() => {
                              const next = checked
                                ? (field.value ?? []).filter((c: Channel) => c !== ch)
                                : [...(field.value ?? []), ch]
                              field.onChange(next)
                            }}
                            className="rounded border-gray-300 text-blue-600"
                          />
                          <span className="text-sm">{CHANNEL_LABELS[ch]}</span>
                        </label>
                      )
                    })}
                  </div>
                )}
              />
            </div>
          </div>
        </Card>

        <Card title="Schedule & Expiry">
          <div className="grid grid-cols-2 gap-4">
            <Input
              label="Schedule At"
              type="datetime-local"
              hint="Leave blank to send immediately"
              error={errors.scheduledAt?.message}
              {...register('scheduledAt')}
            />
            <Input
              label="Expires At"
              type="datetime-local"
              required
              error={errors.expiresAt?.message}
              {...register('expiresAt')}
            />
          </div>
        </Card>

        <Card title="Template Variables">
          <div className="space-y-3">
            {fields.map((field, index) => (
              <div key={field.id} className="flex gap-2 items-start">
                <Input
                  placeholder="variable name"
                  className="flex-1"
                  {...register(`variables.${index}.key`)}
                />
                <Input
                  placeholder="value"
                  className="flex-1"
                  {...register(`variables.${index}.value`)}
                />
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => remove(index)}
                  className="mt-0.5 text-red-500"
                >
                  ✕
                </Button>
              </div>
            ))}
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => append({ key: '', value: '' })}
            >
              + Add variable
            </Button>
          </div>
        </Card>

        <Card title="Producer Context">
          <div className="grid grid-cols-2 gap-4">
            <Input
              label="Producer Service"
              required
              error={errors.producerService?.message}
              {...register('producerService')}
            />
            <Input
              label="Trace ID"
              required
              error={errors.producerTraceId?.message}
              {...register('producerTraceId')}
            />
          </div>
        </Card>

        <div className="flex gap-3">
          <Button type="submit" loading={isPending}>
            Submit Notification
          </Button>
          <Button type="button" variant="secondary" onClick={() => navigate(-1)}>
            Cancel
          </Button>
        </div>
      </form>
    </div>
  )
}
