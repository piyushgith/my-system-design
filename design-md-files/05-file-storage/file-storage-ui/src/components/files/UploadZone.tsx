import { useCallback, useRef, useState } from 'react'
import type { UploadProgress } from '@/types/api'
import { formatBytes } from '@/utils/format'

interface UploadZoneProps {
  onFilesSelected: (files: FileList | File[]) => void
  isUploading: boolean
  progress: UploadProgress | null
}

export function UploadZone({ onFilesSelected, isUploading, progress }: UploadZoneProps) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [isDragging, setIsDragging] = useState(false)

  const handleDrop = useCallback(
    (event: React.DragEvent<HTMLDivElement>) => {
      event.preventDefault()
      setIsDragging(false)
      if (event.dataTransfer.files.length > 0) {
        onFilesSelected(event.dataTransfer.files)
      }
    },
    [onFilesSelected],
  )

  const percent =
    progress && progress.totalBytes > 0
      ? Math.round((progress.bytesUploaded / progress.totalBytes) * 100)
      : 0

  return (
    <section
      className={`relative overflow-hidden rounded-3xl border-2 border-dashed transition-all duration-300 ${
        isDragging
          ? 'border-vault-teal bg-vault-teal/5 shadow-glow'
          : 'border-vault-border bg-vault-panel/40 hover:border-vault-teal/30'
      }`}
      onDragOver={(e) => {
        e.preventDefault()
        setIsDragging(true)
      }}
      onDragLeave={() => setIsDragging(false)}
      onDrop={handleDrop}
    >
      <div className="pointer-events-none absolute -right-16 -top-16 h-48 w-48 rounded-full bg-vault-teal/5 blur-3xl" />
      <div className="pointer-events-none absolute -bottom-20 -left-10 h-40 w-40 rounded-full bg-vault-brass/5 blur-3xl" />

      <div className="relative px-8 py-12 text-center">
        <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-2xl border border-vault-border bg-vault-bg font-display text-3xl text-vault-brass">
          ↑
        </div>
        <h2 className="font-display text-3xl text-vault-text">Deposit into the vault</h2>
        <p className="mx-auto mt-3 max-w-xl text-sm leading-relaxed text-vault-muted">
          Drop files here or browse. Large files try presigned PUT to MinIO when enabled; otherwise
          multipart via{' '}
          <code className="rounded bg-vault-bg px-1.5 py-0.5 font-mono text-vault-teal">/api/v1/uploads</code>.
        </p>

        <button
          type="button"
          disabled={isUploading}
          onClick={() => inputRef.current?.click()}
          className="mt-8 inline-flex items-center gap-2 rounded-xl border border-vault-teal/40 bg-vault-teal/10 px-6 py-3 text-sm font-medium text-vault-teal transition hover:bg-vault-teal/20 disabled:opacity-50"
        >
          {isUploading ? 'Archiving…' : 'Select files'}
        </button>

        <input
          ref={inputRef}
          type="file"
          multiple
          aria-label="Upload files to the vault"
          className="hidden"
          onChange={(e) => {
            if (e.target.files?.length) onFilesSelected(e.target.files)
            e.target.value = ''
          }}
        />

        {progress ? (
          <div className="mx-auto mt-8 max-w-md text-left">
            <div className="mb-2 flex items-center justify-between font-mono text-xs text-vault-muted">
              <span>{progress.fileName}</span>
              <span>{percent}%</span>
            </div>
            <div className="h-2 overflow-hidden rounded-full bg-vault-bg">
              <div
                className="h-full rounded-full bg-gradient-to-r from-vault-teal to-vault-brass transition-all duration-300"
                style={{ width: `${percent}%` }}
              />
            </div>
            <p className="mt-2 font-mono text-xs text-vault-muted">
              {formatBytes(progress.bytesUploaded)} / {formatBytes(progress.totalBytes)} · {progress.phase}
              {progress.message ? ` · ${progress.message}` : ''}
            </p>
          </div>
        ) : null}
      </div>
    </section>
  )
}
