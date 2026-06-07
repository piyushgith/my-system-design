import { Link, useLocation } from 'react-router-dom'
import { useHistoryStore } from '@/store/historyStore'

interface MainLayoutProps {
  children: React.ReactNode
}

export function MainLayout({ children }: Readonly<MainLayoutProps>) {
  const location = useLocation()
  const historyCount = useHistoryStore((s) => s.entries.length)

  return (
    <div className="min-h-screen flex flex-col" style={{ backgroundColor: '#06060f' }}>
      <header
        className="border-b border-gray-800/60 sticky top-0 z-10 backdrop-blur-md"
        style={{ backgroundColor: 'rgba(6,6,15,0.85)' }}
      >
        <div className="max-w-3xl mx-auto px-4 py-4 flex items-center justify-between">
          <Link to="/" className="flex items-center gap-2.5 group">
            <div className="h-7 w-7 rounded flex items-center justify-center bg-brand-300/10 border border-brand-300/25 group-hover:border-brand-300/50 transition-colors">
              <svg
                className="h-3.5 w-3.5 text-brand-300"
                viewBox="0 0 20 20"
                fill="currentColor"
                aria-hidden="true"
              >
                <path d="M12.232 4.232a2.5 2.5 0 0 1 3.536 3.536l-1.225 1.224a.75.75 0 0 0 1.061 1.06l1.224-1.224a4 4 0 0 0-5.656-5.656l-3 3a4 4 0 0 0 .225 5.865.75.75 0 0 0 .977-1.138 2.5 2.5 0 0 1-.142-3.667l3-3Z" />
                <path d="M11.603 7.963a.75.75 0 0 0-.977 1.138 2.5 2.5 0 0 1 .142 3.667l-3 3a2.5 2.5 0 0 1-3.536-3.536l1.225-1.224a.75.75 0 0 0-1.061-1.06l-1.224 1.224a4 4 0 1 0 5.656 5.656l3-3a4 4 0 0 0-.225-5.865Z" />
              </svg>
            </div>
            <span className="text-sm font-extrabold tracking-widest uppercase text-gray-100 group-hover:text-brand-300 transition-colors font-display">
              snip<span className="text-brand-300">.</span>it
            </span>
          </Link>

          <nav className="flex items-center gap-1">
            <Link
              to="/"
              className={[
                'px-3 py-1.5 rounded text-xs font-bold uppercase tracking-widest transition-colors font-display',
                location.pathname === '/'
                  ? 'text-brand-300 bg-brand-300/10'
                  : 'text-gray-500 hover:text-gray-200 hover:bg-gray-800/60',
              ].join(' ')}
            >
              Shorten
            </Link>
            <Link
              to="/history"
              className={[
                'px-3 py-1.5 rounded text-xs font-bold uppercase tracking-widest transition-colors flex items-center gap-1.5 font-display',
                location.pathname === '/history'
                  ? 'text-brand-300 bg-brand-300/10'
                  : 'text-gray-500 hover:text-gray-200 hover:bg-gray-800/60',
              ].join(' ')}
            >
              History
              {historyCount > 0 && (
                <span className="inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-brand-300 px-1 text-[9px] font-bold text-gray-950">
                  {historyCount > 99 ? '99+' : historyCount}
                </span>
              )}
            </Link>
          </nav>
        </div>
      </header>

      <main className="flex-1 bg-grid">
        <div className="max-w-3xl mx-auto px-4 py-10">{children}</div>
      </main>

      <footer className="border-t border-gray-800/40">
        <div className="max-w-3xl mx-auto px-4 py-4 flex items-center justify-between">
          <span className="text-xs text-gray-700 font-display font-bold tracking-widest uppercase">snip.it</span>
          <span className="text-xs text-gray-700">
            API:{' '}
            <code className="font-mono text-gray-600">localhost:8080</code>
          </span>
        </div>
      </footer>
    </div>
  )
}
