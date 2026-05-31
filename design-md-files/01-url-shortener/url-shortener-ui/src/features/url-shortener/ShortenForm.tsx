import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { useCreateUrl } from '@/hooks/useCreateUrl'
import { TTL_OPTIONS } from '@/constants/ttlOptions'
import type { CreateUrlResponse, ApiError } from '@/types/api'

const schema = z.object({
  longUrl: z
    .string()
    .min(1, 'URL is required')
    .url('Must be a valid URL (include https://)')
    .max(2048, 'URL must be under 2048 characters'),
  alias: z
    .string()
    .optional()
    .refine(
      (v) => !v || /^[a-zA-Z0-9_-]{3,10}$/.test(v),
      'Alias: 3–10 chars, letters/numbers/_/-  only'
    ),
  ttl: z.string().optional(),
})

type FormValues = z.infer<typeof schema>

interface ShortenFormProps {
  onSuccess: (result: CreateUrlResponse) => void
}

export function ShortenForm({ onSuccess }: ShortenFormProps) {
  const [showAdvanced, setShowAdvanced] = useState(false)
  const { mutate, isPending } = useCreateUrl()

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<FormValues>({ resolver: zodResolver(schema) })

  function mapApiError(apiErr: ApiError) {
    const { code, message, field } = apiErr.error
    if (field === 'alias' || code === 'INVALID_ALIAS' || code === 'ALIAS_CONFLICT' || code === 'RESERVED_ALIAS') {
      setError('alias', { message })
    } else if (field === 'longUrl' || code === 'INVALID_URL') {
      setError('longUrl', { message })
    } else {
      setError('root', { message })
    }
  }

  function onSubmit(values: FormValues) {
    mutate(
      {
        longUrl: values.longUrl,
        alias: values.alias || undefined,
        ttl: values.ttl ? Number(values.ttl) : undefined,
      },
      {
        onSuccess: (data) => {
          reset()
          setShowAdvanced(false)
          onSuccess(data)
        },
        onError: (err) => mapApiError(err),
      }
    )
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="flex flex-col gap-4">
      {/* URL input */}
      <div className="flex gap-2">
        <div className="flex-1">
          <Input
            label="Long URL"
            type="url"
            placeholder="https://example.com/very/long/url/here"
            error={errors.longUrl?.message}
            {...register('longUrl')}
          />
        </div>
        <Button
          type="submit"
          size="lg"
          loading={isPending}
          className="mt-6 shrink-0 whitespace-nowrap"
        >
          Shorten
        </Button>
      </div>

      {/* Advanced options toggle */}
      <button
        type="button"
        onClick={() => setShowAdvanced((v) => !v)}
        className="self-start text-xs text-brand-600 hover:text-brand-800 underline-offset-2 hover:underline transition-colors"
      >
        {showAdvanced ? '− Hide options' : '+ Custom alias & expiry'}
      </button>

      {showAdvanced && (
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 animate-slide-up">
          <Input
            label="Custom alias (optional)"
            placeholder="my-link"
            hint="3–10 chars: letters, numbers, _ or -"
            error={errors.alias?.message}
            {...register('alias')}
          />

          <div className="flex flex-col gap-1">
            <label htmlFor="ttl" className="text-sm font-medium text-gray-700">
              Expiry
            </label>
            <select
              id="ttl"
              className="rounded-lg border border-gray-300 px-3 py-2 text-sm text-gray-900 focus:outline-none focus:ring-2 focus:ring-brand-500 focus:border-brand-500"
              {...register('ttl')}
            >
              {TTL_OPTIONS.map((opt) => (
                <option key={opt.label} value={opt.value ?? ''}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>
        </div>
      )}

      {/* Root-level API error */}
      {errors.root && (
        <p className="text-sm text-red-600" role="alert">
          {errors.root.message}
        </p>
      )}
    </form>
  )
}
