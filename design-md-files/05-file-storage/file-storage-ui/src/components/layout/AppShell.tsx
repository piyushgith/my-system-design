import { useSettingsStore } from '@/store/settingsStore'

interface AppShellProps {
  children: React.ReactNode
  totalItems?: number
}

export function AppShell({ children, totalItems = 0 }: AppShellProps) {
  const ownerId = useSettingsStore((s) => s.ownerId)
  const setOwnerId = useSettingsStore((s) => s.setOwnerId)
  const preferPresigned = useSettingsStore((s) => s.preferPresigned)
  const setPreferPresigned = useSettingsStore((s) => s.setPreferPresigned)

  return (
    <div className="vault-grid min-h-screen bg-vault-bg text-vault-text">
      <header className="sticky top-0 z-30 border-b border-vault-border/80 bg-vault-bg/85 backdrop-blur-xl">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4 px-6 py-5">
          <div className="flex items-center gap-4">
            <div className="relative flex h-11 w-11 items-center justify-center rounded-2xl border border-vault-border bg-vault-panel">
              <span className="font-display text-xl text-vault-teal">V</span>
              <span className="absolute -right-1 -top-1 h-2.5 w-2.5 animate-pulse-soft rounded-full bg-vault-brass" />
            </div>
            <div>
              <p className="font-mono text-[11px] uppercase tracking-[0.28em] text-vault-muted">File Storage</p>
              <h1 className="font-display text-2xl italic text-vault-text">Archive Vault</h1>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-4">
            <div className="rounded-xl border border-vault-border bg-vault-panel px-4 py-2">
              <p className="font-mono text-[10px] uppercase tracking-widest text-vault-muted">Indexed objects</p>
              <p className="font-display text-xl text-vault-brass">{totalItems.toLocaleString()}</p>
            </div>

            <label className="flex items-center gap-2 rounded-xl border border-vault-border bg-vault-panel px-4 py-2">
              <input
                id="prefer-presigned"
                type="checkbox"
                checked={preferPresigned}
                onChange={(e) => setPreferPresigned(e.target.checked)}
                className="h-4 w-4 accent-vault-teal"
              />
              <span className="font-mono text-[10px] uppercase tracking-widest text-vault-muted">
                Presigned (MinIO)
              </span>
            </label>

            <label className="flex flex-col gap-1">
              <span className="font-mono text-[10px] uppercase tracking-widest text-vault-muted">X-Owner-Id</span>
              <input
                value={ownerId}
                onChange={(e) => setOwnerId(e.target.value)}
                placeholder="optional owner"
                className="w-48 rounded-lg border border-vault-border bg-vault-bg px-3 py-2 font-mono text-sm text-vault-text outline-none transition focus:border-vault-teal"
              />
            </label>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-7xl px-6 py-10">{children}</main>

      <footer className="border-t border-vault-border/60 py-6 text-center font-mono text-xs text-vault-muted">
        Spring Boot · PostgreSQL/H2 · local &amp; MinIO backends
      </footer>
    </div>
  )
}
