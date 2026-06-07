import { useState, useEffect } from 'react'
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

const PLACEHOLDER_URLS = [
  'https://example.com/very/long/url/here',
  'https://github.com/user/repo/blob/main/README.md',
  'https://docs.company.com/api/v2/reference/endpoints',
  'https://youtube.com/watch?v=dQw4w9WgXcQ',
]

interface ShortenFormProps {
  onSuccess: (result: CreateUrlResponse) => void
}

export function ShortenForm({ onSuccess }: Readonly<ShortenFormProps>) {
  const [showAdvanced, setShowAdvanced] = useState(false)
  const [placeholderIdx, setPlaceholderIdx] = useState(0)
  const { mutate, isPending } = useCreateUrl()

  useEffect(() => {
    const id = setInterval(() => {
      setPlaceholderIdx((i) => (i + 1) % PLACEHOLDER_URLS.length)
    }, 3000)
    return () => clearInterval(id)
  }, [])

  const {
    register,
    handleSubmit,
    setError,
    reset,
    setValue,
    formState: { errors },
  } = useForm<FormValues>({ resolver: zodResolver(schema) })

  async function handlePaste() {
    try {
      const text = await navigator.clipboard.readText()
      setValue('longUrl', text, { shouldValidate: true })
    } catch {
      // clipboard access denied — silently ignore
    }
  }

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
            placeholder={PLACEHOLDER_URLS[placeholderIdx]}
            error={errors.longUrl?.message}
            suffix={
              <button
                type="button"
                onClick={handlePaste}
                title="Paste from clipboard"
                className="p-1 text-gray-600 hover:text-brand-300 transition-colors"
              >
                <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24" aria-hidden="true">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
                </svg>
              </button>
            }
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
        className="self-start text-xs text-gray-600 hover:text-brand-300 font-mono tracking-wide transition-colors"
      >
        {showAdvanced ? '[ − hide options ]' : '[ + alias & expiry ]'}
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
            <label htmlFor="ttl" className="text-sm font-medium text-gray-400 font-display">
              Expiry
            </label>
            <select
              id="ttl"
              className="rounded-lg border border-gray-700 hover:border-gray-600 bg-gray-900/80 px-3 py-2 text-sm text-gray-200 font-mono focus:outline-none focus:ring-2 focus:ring-brand-300/40 focus:border-brand-300/40 transition-colors"
              {...register('ttl')}
            >
              {TTL_OPTIONS.map((opt) => (
                <option key={opt.label} value={opt.value ?? ''} className="bg-gray-900">
                  {opt.label}
                </option>
              ))}
            </select>
          </div>
        </div>
      )}

      {/* Root-level API error */}
      {errors.root && (
        <p className="text-sm text-red-400 font-mono" role="alert">
          {'✗ '}{errors.root.message}
        </p>
      )}
    </form>
  )
}
