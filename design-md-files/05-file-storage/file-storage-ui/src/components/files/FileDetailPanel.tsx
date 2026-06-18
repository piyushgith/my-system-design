import { useEffect, useEffectEvent } from 'react'
import { formatBytes, formatDate, truncateMiddle } from '@/utils/format'
import type { FileMetadataResponse } from '@/types/api'
import { Button } from '@/components/ui/Button'

interface FileDetailPanelProps {
  file: FileMetadataResponse | null
  onClose: () => void
  onDownload: (fileId: string) => void
}

export function FileDetailPanel({ file, onClose, onDownload }: FileDetailPanelProps) {
  const onEscape = useEffectEvent(onClose)

  useEffect(() => {
    if (!file) return
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onEscape()
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [file])

  if (!file) return null

  return (
    <>
      <button
        type="button"
        className="fixed inset-0 z-40 bg-black/50 backdrop-blur-sm"
        onClick={onClose}
        aria-label="Close file details"
      />
      <aside
        className="fixed right-0 top-0 z-50 flex h-full w-full max-w-md flex-col border-l border-vault-border bg-vault-surface shadow-vault animate-fade-up"
        aria-label="File details"
      >
        <header className="flex items-center justify-between border-b border-vault-border px-6 py-5">
          <div>
            <p className="font-mono text-xs uppercase tracking-[0.2em] text-vault-teal">Manifest</p>
            <h2 className="mt-1 font-display text-2xl text-vault-text">{file.name}</h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-vault-border px-3 py-1 font-mono text-sm text-vault-muted hover:text-vault-text"
            aria-label="Close panel"
          >
            esc
          </button>
        </header>

        <div className="flex-1 space-y-4 overflow-y-auto px-6 py-6">
          {[
            ['File ID', file.fileId],
            ['MIME type', file.mimeType || 'application/octet-stream'],
            ['Size', formatBytes(file.sizeBytes)],
            ['Backend', file.backend],
            ['Owner', file.ownerId || '—'],
            ['Created', formatDate(file.createdAt)],
            ['Content hash', file.contentHash],
            ['Download path', file.downloadUrl],
          ].map(([label, value]) => (
            <div key={label} className="rounded-xl border border-vault-border bg-vault-panel/70 p-4">
              <p className="font-mono text-[11px] uppercase tracking-widest text-vault-muted">{label}</p>
              <p
                className="mt-2 break-all font-mono text-sm text-vault-text"
                title={typeof value === 'string' ? value : undefined}
              >
                {label === 'Content hash' || label === 'File ID'
                  ? truncateMiddle(String(value), 14, 10)
                  : value}
              </p>
            </div>
          ))}
        </div>

        <footer className="border-t border-vault-border px-6 py-5">
          <Button className="w-full" onClick={() => onDownload(file.fileId)}>
            Retrieve from vault
          </Button>
        </footer>
      </aside>
    </>
  )
}
