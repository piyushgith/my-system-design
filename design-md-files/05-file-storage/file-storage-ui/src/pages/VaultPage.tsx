import { useCallback, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { getDownloadUrl } from '@/api/files'
import { FileCard } from '@/components/files/FileCard'
import { FileDetailPanel } from '@/components/files/FileDetailPanel'
import { UploadZone } from '@/components/files/UploadZone'
import { AppShell } from '@/components/layout/AppShell'
import { Button } from '@/components/ui/Button'
import { usePagination } from '@/hooks/useFileList'
import { useUpload } from '@/hooks/useUpload'
import type { FileMetadataResponse } from '@/types/api'
import { extractApiError } from '@/api/client'

export function VaultPage() {
  const queryClient = useQueryClient()
  const [selectedFile, setSelectedFile] = useState<FileMetadataResponse | null>(null)

  const {
    page,
    setPage,
    data,
    isLoading,
    isError,
    error,
    refetch,
    handleDelete,
    isDeleting,
    totalPages,
    canPrev,
    canNext,
  } = usePagination()

  const { upload, isUploading, progress } = useUpload(() => {
    queryClient.invalidateQueries({ queryKey: ['files'] })
  })

  const handleFilesSelected = useCallback(
    async (files: FileList | File[]) => {
      const list = Array.from(files)
      for (const file of list) {
        await upload(file)
      }
    },
    [upload],
  )

  const handleDownload = useCallback((fileId: string) => {
    const anchor = document.createElement('a')
    anchor.href = getDownloadUrl(fileId)
    anchor.download = ''
    anchor.rel = 'noopener'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
  }, [])

  return (
    <AppShell totalItems={data?.totalItems ?? 0}>
      <div className="space-y-12">
        <UploadZone
          onFilesSelected={handleFilesSelected}
          isUploading={isUploading}
          progress={progress}
        />

        <section>
          <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
            <div>
              <p className="font-mono text-xs uppercase tracking-[0.24em] text-vault-teal">Catalog</p>
              <h2 className="mt-1 font-display text-3xl text-vault-text">Stored artifacts</h2>
            </div>
            <Button variant="ghost" onClick={() => refetch()}>
              Refresh index
            </Button>
          </div>

          {isLoading ? (
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
              {Array.from({ length: 6 }).map((_, i) => (
                <div
                  key={i}
                  className="h-56 animate-pulse rounded-2xl border border-vault-border bg-vault-panel/50"
                />
              ))}
            </div>
          ) : null}

          {isError ? (
            <div className="rounded-2xl border border-vault-danger/30 bg-vault-danger/5 p-6 text-vault-danger">
              {extractApiError(error)}
            </div>
          ) : null}

          {!isLoading && !isError && data?.items.length === 0 ? (
            <div className="rounded-2xl border border-vault-border bg-vault-panel/30 px-8 py-16 text-center">
              <p className="font-display text-2xl text-vault-muted">The vault is empty</p>
              <p className="mt-2 text-sm text-vault-muted">Upload your first file to populate the catalog.</p>
            </div>
          ) : null}

          {!isLoading && !isError && data && data.items.length > 0 ? (
            <>
              <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
                {data.items.map((file, index) => (
                  <FileCard
                    key={file.fileId}
                    file={file}
                    index={index}
                    onSelect={setSelectedFile}
                    onDownload={handleDownload}
                    onDelete={handleDelete}
                    isDeleting={isDeleting}
                  />
                ))}
              </div>

              {totalPages > 1 ? (
                <div className="mt-8 flex items-center justify-center gap-3">
                  <Button variant="ghost" disabled={!canPrev} onClick={() => setPage(page - 1)}>
                    Previous
                  </Button>
                  <span className="font-mono text-sm text-vault-muted">
                    Page {page + 1} of {totalPages}
                  </span>
                  <Button variant="ghost" disabled={!canNext} onClick={() => setPage(page + 1)}>
                    Next
                  </Button>
                </div>
              ) : null}
            </>
          ) : null}
        </section>
      </div>

      <FileDetailPanel
        file={selectedFile}
        onClose={() => setSelectedFile(null)}
        onDownload={handleDownload}
      />
    </AppShell>
  )
}
