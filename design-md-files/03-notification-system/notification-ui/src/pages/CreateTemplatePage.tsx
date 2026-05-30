import { useForm, useFieldArray } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useNavigate } from 'react-router-dom'
import { useCreateTemplate } from '../hooks/useTemplates'
import { useAuthStore } from '../store/auth'
import { useToast } from '../components/ui/Toast'
import { Input } from '../components/ui/Input'
import { Select } from '../components/ui/Select'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { extractErrorMessage } from '../utils/errors'
import { ALL_CHANNELS, CHANNEL_LABELS } from '../constants'

const schema = z.object({
  templateId: z
    .string()
    .min(1, 'Template ID required')
    .regex(/^[a-z0-9-]+$/, 'Lowercase letters, numbers, hyphens only'),
  channel: z.enum(['EMAIL', 'SMS', 'PUSH', 'IN_APP']),
  locale: z.string().min(1, 'Locale required').default('en'),
  subject: z.string().optional(),
  bodyHtml: z.string().optional(),
  bodyText: z.string().optional(),
  pushTitle: z.string().optional(),
  pushBody: z.string().optional(),
  variablesSchema: z
    .array(z.object({ key: z.string().min(1, 'Variable name required'), description: z.string() }))
    .optional(),
})

type FormValues = z.infer<typeof schema>

export function CreateTemplatePage() {
  const navigate = useNavigate()
  const { user } = useAuthStore()
  const { addToast } = useToast()
  const { mutateAsync, isPending } = useCreateTemplate()

  const {
    register,
    control,
    watch,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      channel: 'EMAIL',
      locale: 'en',
      variablesSchema: [],
    },
  })

  const { fields, append, remove } = useFieldArray({ control, name: 'variablesSchema' })
  const channel = watch('channel')

  async function onSubmit(values: FormValues) {
    const variablesSchema: Record<string, string> = {}
    for (const v of values.variablesSchema ?? []) {
      if (v.key) variablesSchema[v.key] = v.description
    }

    try {
      const result = await mutateAsync({
        templateId: values.templateId,
        channel: values.channel,
        locale: values.locale,
        subject: values.subject || undefined,
        bodyHtml: values.bodyHtml || undefined,
        bodyText: values.bodyText || undefined,
        pushTitle: values.pushTitle || undefined,
        pushBody: values.pushBody || undefined,
        variablesSchema,
        createdBy: user!.userId,
      })
      addToast('success', `Template "${result.templateId}" v${result.version} created`)
      navigate('/templates')
    } catch (err) {
      addToast('error', extractErrorMessage(err))
    }
  }

  return (
    <div className="space-y-6 max-w-2xl">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Create Template</h1>
        <p className="mt-1 text-sm text-gray-500">
          Templates are per-channel. One template ID can have multiple channel variants.
        </p>
      </div>

      <form onSubmit={handleSubmit(onSubmit)} className="space-y-5">
        <Card title="Identity">
          <div className="space-y-4">
            <Input
              label="Template ID"
              placeholder="welcome-email"
              hint="Kebab-case, used when submitting notifications"
              required
              error={errors.templateId?.message}
              {...register('templateId')}
            />
            <div className="grid grid-cols-2 gap-4">
              <Select
                label="Channel"
                required
                error={errors.channel?.message}
                options={ALL_CHANNELS.map((c) => ({ value: c, label: CHANNEL_LABELS[c] }))}
                {...register('channel')}
              />
              <Input
                label="Locale"
                placeholder="en"
                required
                error={errors.locale?.message}
                {...register('locale')}
              />
            </div>
          </div>
        </Card>

        {(channel === 'EMAIL') && (
          <Card title="Email Content">
            <div className="space-y-4">
              <Input
                label="Subject"
                placeholder="Welcome to {{app_name}}!"
                error={errors.subject?.message}
                {...register('subject')}
              />
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Body HTML
                </label>
                <textarea
                  rows={5}
                  placeholder="<p>Hello {{user_name}},</p>"
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-500"
                  {...register('bodyHtml')}
                />
              </div>
            </div>
          </Card>
        )}

        {(channel === 'SMS' || channel === 'IN_APP') && (
          <Card title="Body Text">
            <textarea
              rows={4}
              placeholder="Hello {{user_name}}, your {{event}} has been confirmed."
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              {...register('bodyText')}
            />
          </Card>
        )}

        {channel === 'PUSH' && (
          <Card title="Push Content">
            <div className="space-y-4">
              <Input
                label="Push Title"
                placeholder="New message from {{sender}}"
                error={errors.pushTitle?.message}
                {...register('pushTitle')}
              />
              <Input
                label="Push Body"
                placeholder="{{preview_text}}"
                error={errors.pushBody?.message}
                {...register('pushBody')}
              />
            </div>
          </Card>
        )}

        <Card title="Variable Schema">
          <div className="space-y-3">
            <p className="text-xs text-gray-500">
              Declare variables used in the template body. Backend validates submissions include all
              declared keys.
            </p>
            {fields.map((field, index) => (
              <div key={field.id} className="flex gap-2 items-start">
                <Input
                  placeholder="variable_name"
                  className="flex-1"
                  error={errors.variablesSchema?.[index]?.key?.message}
                  {...register(`variablesSchema.${index}.key`)}
                />
                <Input
                  placeholder="description"
                  className="flex-1"
                  {...register(`variablesSchema.${index}.description`)}
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
              onClick={() => append({ key: '', description: '' })}
            >
              + Add variable
            </Button>
          </div>
        </Card>

        <div className="flex gap-3">
          <Button type="submit" loading={isPending}>
            Create Template
          </Button>
          <Button type="button" variant="secondary" onClick={() => navigate('/templates')}>
            Cancel
          </Button>
        </div>
      </form>
    </div>
  )
}
