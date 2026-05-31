import { useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Toast } from '@/components/ui/Toast'
import { copyToClipboard } from '@/utils/clipboard'
import { formatDateTime } from '@/utils/time'
import { useHistoryStore } from '@/store/historyStore'

export function UrlHistory() {
  const { entries, remove, clear } = useHistoryStore()
  const [toastMessage, setToastMessage] = useState<string | null>(null)

  if (entries.length === 0) {
    return (
      <div className="text-center py-12 text-gray-400">
        <svg
          className="mx-auto h-12 w-12 mb-3 opacity-40"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
          aria-hidden="true"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M13.19 8.688a4.5 4.5 0 0 1 1.242 7.244l-4.5 4.5a4.5 4.5 0 0 1-6.364-6.364l1.757-1.757m13.35-.622 1.757-1.757a4.5 4.5 0 0 0-6.364-6.364l-4.5 4.5a4.5 4.5 0 0 0 1.242 7.244"
          />
        </svg>
        <p className="text-sm">No shortened URLs yet.</p>
        <p className="text-xs mt-1">URLs you create will appear here.</p>
      </div>
    )
  }

  async function handleCopy(url: string) {
    const ok = await copyToClipboard(url)
    setToastMessage(ok ? 'Copied!' : 'Copy failed')
  }

  return (
    <>
      <div className="flex items-center justify-between mb-4">
        <p className="text-sm text-gray-500">
          {entries.length} URL{entries.length !== 1 ? 's' : ''} in history
        </p>
        <Button variant="ghost" size="sm" onClick={clear} className="text-red-500 hover:text-red-700 hover:bg-red-50">
          Clear all
        </Button>
      </div>

      <ul className="flex flex-col gap-3" role="list">
        {entries.map((entry) => {
          const isExpired = entry.expiresAt && new Date(entry.expiresAt) < new Date()
          return (
            <li
              key={`${entry.shortCode}-${entry.savedAt}`}
              className="flex items-start gap-3 rounded-lg border border-gray-200 bg-white p-4 hover:border-gray-300 transition-colors"
            >
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2 flex-wrap mb-1">
                  <a
                    href={entry.shortUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="font-mono text-sm font-semibold text-brand-600 hover:underline"
                  >
                    {entry.shortUrl}
                  </a>
                  {isExpired ? (
                    <Badge variant="red">Expired</Badge>
                  ) : entry.expiresAt ? (
                    <Badge variant="yellow">Expires {formatDateTime(entry.expiresAt)}</Badge>
                  ) : (
                    <Badge variant="green">Active</Badge>
                  )}
                </div>
                <p className="text-xs text-gray-500 truncate">{entry.longUrl}</p>
                <p className="text-xs text-gray-400 mt-0.5">
                  Saved {formatDateTime(entry.savedAt)}
                </p>
              </div>

              <div className="flex items-center gap-1 shrink-0">
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => handleCopy(entry.shortUrl)}
                  aria-label="Copy short URL"
                >
                  Copy
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => remove(entry.shortCode)}
                  className="text-gray-400 hover:text-red-600 hover:bg-red-50"
                  aria-label="Remove from history"
                >
                  ✕
                </Button>
              </div>
            </li>
          )
        })}
      </ul>

      {toastMessage && (
        <Toast message={toastMessage} onClose={() => setToastMessage(null)} />
      )}
    </>
  )
}
