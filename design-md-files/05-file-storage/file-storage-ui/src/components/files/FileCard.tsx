import { formatBytes, formatDate, mimeCategory, truncateMiddle } from '@/utils/format'
import type { FileMetadataResponse } from '@/types/api'
import { Button } from '@/components/ui/Button'

interface FileCardProps {
  file: FileMetadataResponse
  index: number
  onSelect: (file: FileMetadataResponse) => void
  onDownload: (fileId: string) => void
  onDelete: (fileId: string) => void
  isDeleting: boolean
}

const categoryGlyph: Record<string, string> = {
  image: '◈',
  video: '▶',
  audio: '♫',
  pdf: '§',
  text: '¶',
  archive: '⊞',
  file: '◻',
  binary: '◎',
}

export function FileCard({ file, index, onSelect, onDownload, onDelete, isDeleting }: FileCardProps) {
  const category = mimeCategory(file.mimeType)

  return (
    <article
      className="group relative animate-fade-up overflow-hidden rounded-2xl border border-vault-border bg-vault-panel/80 p-5 transition duration-300 hover:-translate-y-1 hover:border-vault-teal/40 hover:shadow-glow"
      style={{ animationDelay: `${index * 60}ms` }}
    >
      <div className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-vault-teal/50 to-transparent opacity-0 transition group-hover:opacity-100" />

      <button
        type="button"
        onClick={() => onSelect(file)}
        className="mb-4 flex w-full items-start gap-4 text-left"
        aria-label={`View details for ${file.name}`}
      >
        <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl border border-vault-border bg-vault-bg font-mono text-xl text-vault-brass">
          {categoryGlyph[category]}
        </div>
        <div className="min-w-0 flex-1">
          <h3 className="truncate font-display text-lg text-vault-text">{file.name}</h3>
          <p className="mt-1 font-mono text-xs text-vault-muted">
            {formatBytes(file.sizeBytes)} · {file.backend}
          </p>
        </div>
      </button>

      <dl className="mb-4 grid grid-cols-2 gap-2 text-xs">
        <div className="rounded-lg bg-vault-bg/60 px-3 py-2">
          <dt className="text-vault-muted">Stored</dt>
          <dd className="mt-0.5 font-mono text-vault-text">{formatDate(file.createdAt)}</dd>
        </div>
        <div className="rounded-lg bg-vault-bg/60 px-3 py-2">
          <dt className="text-vault-muted">Hash</dt>
          <dd className="mt-0.5 font-mono text-vault-text" title={file.contentHash}>
            {truncateMiddle(file.contentHash, 10, 8)}
          </dd>
        </div>
      </dl>

      <div className="flex gap-2">
        <Button variant="ghost" className="flex-1 text-xs" onClick={() => onDownload(file.fileId)}>
          Download
        </Button>
        <Button
          variant="danger"
          className="text-xs"
          loading={isDeleting}
          onClick={() => onDelete(file.fileId)}
        >
          Delete
        </Button>
      </div>
    </article>
  )
}
