import { useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Toast } from '@/components/ui/Toast'
import { copyToClipboard } from '@/utils/clipboard'
import { formatDateTime, formatRelativeTime } from '@/utils/time'
import type { CreateUrlResponse } from '@/types/api'

interface UrlResultProps {
  result: CreateUrlResponse
  onDismiss: () => void
}

export function UrlResult({ result, onDismiss }: UrlResultProps) {
  const [toastMessage, setToastMessage] = useState<string | null>(null)

  async function handleCopy() {
    const ok = await copyToClipboard(result.shortUrl)
    setToastMessage(ok ? 'Copied to clipboard!' : 'Copy failed — please copy manually')
  }

  const isExpiring = result.expiresAt !== null

  return (
    <>
      <div className="rounded-xl border border-green-200 bg-green-50 p-5 animate-slide-up">
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2 mb-3">
              <span className="text-green-600">
                <svg className="h-5 w-5" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                  <path
                    fillRule="evenodd"
                    d="M10 18a8 8 0 1 0 0-16 8 8 0 0 0 0 16Zm3.857-9.809a.75.75 0 0 0-1.214-.882l-3.483 4.79-1.88-1.88a.75.75 0 1 0-1.06 1.061l2.5 2.5a.75.75 0 0 0 1.137-.089l4-5.5Z"
                    clipRule="evenodd"
                  />
                </svg>
              </span>
              <p className="text-sm font-semibold text-green-800">URL shortened successfully</p>
              {isExpiring && (
                <Badge variant="yellow">
                  Expires in {formatRelativeTime(result.expiresAt!)}
                </Badge>
              )}
            </div>

            {/* Short URL display */}
            <div className="flex items-center gap-2 rounded-lg border border-green-300 bg-white px-3 py-2.5 mb-3">
              <a
                href={result.shortUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="flex-1 min-w-0 text-brand-600 font-mono text-sm font-medium hover:underline truncate"
              >
                {result.shortUrl}
              </a>
              <Button size="sm" onClick={handleCopy} className="shrink-0">
                Copy
              </Button>
            </div>

            {/* Metadata */}
            <div className="flex flex-wrap gap-x-6 gap-y-1 text-xs text-gray-500">
              <span>
                Code: <span className="font-mono text-gray-700">{result.shortCode}</span>
              </span>
              <span>Created: {formatDateTime(result.createdAt)}</span>
              {result.expiresAt && (
                <span>Expires: {formatDateTime(result.expiresAt)}</span>
              )}
            </div>

            {/* Original URL */}
            <p className="mt-2 text-xs text-gray-400 truncate">
              → {result.longUrl}
            </p>
          </div>

          <button
            onClick={onDismiss}
            className="shrink-0 rounded p-1 text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors"
            aria-label="Dismiss result"
          >
            <svg className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path d="M6.28 5.22a.75.75 0 0 0-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 1 0 1.06 1.06L10 11.06l3.72 3.72a.75.75 0 1 0 1.06-1.06L11.06 10l3.72-3.72a.75.75 0 0 0-1.06-1.06L10 8.94 6.28 5.22z" />
            </svg>
          </button>
        </div>
      </div>

      {toastMessage && (
        <Toast message={toastMessage} onClose={() => setToastMessage(null)} />
      )}
    </>
  )
}
