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

export function UrlResult({ result, onDismiss }: Readonly<UrlResultProps>) {
  const [toastMessage, setToastMessage] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [dismissing, setDismissing] = useState(false)

  async function handleCopy() {
    const ok = await copyToClipboard(result.shortUrl)
    if (ok) {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } else {
      setToastMessage('Copy failed — please copy manually')
    }
  }

  function handleDismiss() {
    setDismissing(true)
    setTimeout(onDismiss, 260)
  }

  const isExpiring = result.expiresAt !== null

  return (
    <>
      <div
        className={`rounded-xl border border-brand-300/20 bg-brand-300/5 p-5 ${dismissing ? 'animate-fade-out' : 'animate-slide-up'}`}
        style={{ boxShadow: '0 0 60px rgba(200,255,87,0.04), inset 0 1px 0 rgba(200,255,87,0.05)' }}
      >
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2 mb-3">
              <svg className="h-4 w-4 text-brand-300 shrink-0" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                <path
                  fillRule="evenodd"
                  d="M10 18a8 8 0 1 0 0-16 8 8 0 0 0 0 16Zm3.857-9.809a.75.75 0 0 0-1.214-.882l-3.483 4.79-1.88-1.88a.75.75 0 1 0-1.06 1.061l2.5 2.5a.75.75 0 0 0 1.137-.089l4-5.5Z"
                  clipRule="evenodd"
                />
              </svg>
              <p className="text-xs font-mono tracking-widest text-brand-300">LINK COMPRESSED</p>
              {isExpiring && (
                <Badge variant="yellow">
                  {`Expires in ${formatRelativeTime(result.expiresAt!)}`}
                </Badge>
              )}
            </div>

            {/* Short URL — shimmer glow on mount */}
            <div className="flex items-center gap-2 rounded-lg border border-gray-700 bg-gray-900/80 px-3 py-2.5 mb-3 animate-shimmer-once">
              <a
                href={result.shortUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="flex-1 min-w-0 text-brand-300 font-mono text-sm font-medium hover:underline truncate"
              >
                {result.shortUrl}
              </a>
              <Button
                size="sm"
                onClick={handleCopy}
                className={`shrink-0 transition-all duration-200 ${copied ? '!bg-brand-400 !text-gray-950' : ''}`}
              >
                {copied ? '✓ copied' : 'Copy'}
              </Button>
            </div>

            {/* Metadata */}
            <div className="flex flex-wrap gap-x-6 gap-y-1 text-xs text-gray-700 font-mono">
              <span>
                {'code: '}<span className="text-gray-500">{result.shortCode}</span>
              </span>
              <span>{`created: ${formatDateTime(result.createdAt)}`}</span>
              {result.expiresAt && (
                <span>{`expires: ${formatDateTime(result.expiresAt)}`}</span>
              )}
            </div>

            <p className="mt-2 text-xs text-gray-700 truncate font-mono">
              {`→ ${result.longUrl}`}
            </p>
          </div>

          <button
            type="button"
            onClick={handleDismiss}
            className="shrink-0 rounded p-1 text-gray-600 hover:text-gray-300 hover:bg-gray-800 transition-colors"
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
