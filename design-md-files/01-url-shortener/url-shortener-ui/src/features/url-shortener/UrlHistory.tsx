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
  const [copiedCode, setCopiedCode] = useState<string | null>(null)
  const [confirmingCode, setConfirmingCode] = useState<string | null>(null)
  const [removingCodes, setRemovingCodes] = useState<Set<string>>(new Set())

  if (entries.length === 0) {
    return (
      <div className="text-center py-16 animate-fade-in">
        <div className="font-mono text-4xl mb-4 text-gray-800 select-none">∅</div>
        <p className="text-sm text-gray-600 font-display">No shortened URLs yet.</p>
        <p className="text-xs mt-1 text-gray-700 font-mono">URLs you create will appear here.</p>
      </div>
    )
  }

  async function handleCopy(code: string, url: string) {
    const ok = await copyToClipboard(url)
    if (ok) {
      setCopiedCode(code)
      setTimeout(() => setCopiedCode((prev) => (prev === code ? null : prev)), 2000)
    } else {
      setToastMessage('Copy failed')
    }
  }

  function handleRemoveRequest(code: string) {
    setConfirmingCode(code)
  }

  function handleRemoveConfirm(code: string) {
    setConfirmingCode(null)
    setRemovingCodes((prev) => new Set([...prev, code]))
    setTimeout(() => {
      remove(code)
      setRemovingCodes((prev) => {
        const next = new Set(prev)
        next.delete(code)
        return next
      })
    }, 280)
  }

  function handleRemoveCancel() {
    setConfirmingCode(null)
  }

  const now = new Date()

  return (
    <>
      <div className="flex items-center justify-between mb-4">
        <p className="text-xs font-mono text-gray-600">
          {`${entries.length} url${entries.length === 1 ? '' : 's'} stored`}
        </p>
        <Button
          variant="ghost"
          size="sm"
          onClick={clear}
          className="text-red-500/60 hover:text-red-400 hover:bg-red-500/10 font-mono text-xs tracking-wide"
        >
          clear all
        </Button>
      </div>

      <ul className="flex flex-col gap-2">
        {entries.map((entry, index) => {
          const isExpired = entry.expiresAt && new Date(entry.expiresAt) < now
          const isRemoving = removingCodes.has(entry.shortCode)
          const isConfirming = confirmingCode === entry.shortCode
          const isCopied = copiedCode === entry.shortCode

          let badge = <Badge variant="green">Active</Badge>
          if (isExpired) {
            badge = <Badge variant="red">Expired</Badge>
          } else if (entry.expiresAt) {
            badge = <Badge variant="yellow">{`Expires ${formatDateTime(entry.expiresAt)}`}</Badge>
          }

          return (
            <li
              key={`${entry.shortCode}-${entry.savedAt}`}
              className={`flex items-start gap-3 rounded-lg border border-gray-800 bg-gray-900/40 p-4 hover:border-gray-700 hover:bg-gray-900/60 transition-all group animate-slide-up stagger-item ${isRemoving ? 'animate-fade-out' : ''}`}
              style={{ animationDelay: `${index * 55}ms` }}
            >
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2 flex-wrap mb-1">
                  <a
                    href={entry.shortUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="font-mono text-sm font-medium text-brand-300 hover:underline"
                  >
                    {entry.shortUrl}
                  </a>
                  {badge}
                </div>
                <p className="text-xs text-gray-600 truncate font-mono">{entry.longUrl}</p>
                <p className="text-xs text-gray-700 mt-0.5 font-mono">
                  {`saved ${formatDateTime(entry.savedAt)}`}
                </p>
              </div>

              <div className="flex items-center gap-1 shrink-0 opacity-0 group-hover:opacity-100 transition-opacity">
                {isConfirming ? (
                  <>
                    <span className="text-xs text-red-400 font-mono mr-1">delete?</span>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => handleRemoveConfirm(entry.shortCode)}
                      className="text-red-400 hover:text-red-300 hover:bg-red-500/15 font-mono text-xs"
                    >
                      yes
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={handleRemoveCancel}
                      className="text-gray-500 hover:text-gray-300 font-mono text-xs"
                    >
                      no
                    </Button>
                  </>
                ) : (
                  <>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => handleCopy(entry.shortCode, entry.shortUrl)}
                      aria-label="Copy short URL"
                      className={`font-mono text-xs transition-all duration-200 ${isCopied ? 'text-brand-300 bg-brand-300/10' : 'text-gray-500 hover:text-brand-300 hover:bg-brand-300/10'}`}
                    >
                      {isCopied ? '✓' : 'copy'}
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => handleRemoveRequest(entry.shortCode)}
                      className="text-red-400/40 hover:text-red-400 hover:bg-red-500/10"
                      aria-label="Remove from history"
                    >
                      ✕
                    </Button>
                  </>
                )}
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
