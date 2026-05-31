import { Link, useLocation } from 'react-router-dom'
import { useHistoryStore } from '@/store/historyStore'

interface MainLayoutProps {
  children: React.ReactNode
}

export function MainLayout({ children }: MainLayoutProps) {
  const location = useLocation()
  const historyCount = useHistoryStore((s) => s.entries.length)

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      <header className="bg-white border-b border-gray-200 sticky top-0 z-10">
        <div className="max-w-3xl mx-auto px-4 py-4 flex items-center justify-between">
          <Link to="/" className="flex items-center gap-2 group">
            <div className="h-7 w-7 rounded-md bg-brand-600 flex items-center justify-center">
              <svg
                className="h-4 w-4 text-white"
                viewBox="0 0 20 20"
                fill="currentColor"
                aria-hidden="true"
              >
                <path d="M12.232 4.232a2.5 2.5 0 0 1 3.536 3.536l-1.225 1.224a.75.75 0 0 0 1.061 1.06l1.224-1.224a4 4 0 0 0-5.656-5.656l-3 3a4 4 0 0 0 .225 5.865.75.75 0 0 0 .977-1.138 2.5 2.5 0 0 1-.142-3.667l3-3Z" />
                <path d="M11.603 7.963a.75.75 0 0 0-.977 1.138 2.5 2.5 0 0 1 .142 3.667l-3 3a2.5 2.5 0 0 1-3.536-3.536l1.225-1.224a.75.75 0 0 0-1.061-1.06l-1.224 1.224a4 4 0 1 0 5.656 5.656l3-3a4 4 0 0 0-.225-5.865Z" />
              </svg>
            </div>
            <span className="text-base font-semibold text-gray-900 group-hover:text-brand-600 transition-colors">
              URL Shortener
            </span>
          </Link>

          <nav className="flex items-center gap-1">
            <Link
              to="/"
              className={[
                'px-3 py-1.5 rounded-md text-sm font-medium transition-colors',
                location.pathname === '/'
                  ? 'bg-brand-50 text-brand-700'
                  : 'text-gray-600 hover:text-gray-900 hover:bg-gray-100',
              ].join(' ')}
            >
              Shorten
            </Link>
            <Link
              to="/history"
              className={[
                'px-3 py-1.5 rounded-md text-sm font-medium transition-colors flex items-center gap-1.5',
                location.pathname === '/history'
                  ? 'bg-brand-50 text-brand-700'
                  : 'text-gray-600 hover:text-gray-900 hover:bg-gray-100',
              ].join(' ')}
            >
              History
              {historyCount > 0 && (
                <span className="inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-brand-600 px-1 text-[10px] font-medium text-white">
                  {historyCount > 99 ? '99+' : historyCount}
                </span>
              )}
            </Link>
          </nav>
        </div>
      </header>

      <main className="flex-1">
        <div className="max-w-3xl mx-auto px-4 py-8">{children}</div>
      </main>

      <footer className="border-t border-gray-200 bg-white">
        <div className="max-w-3xl mx-auto px-4 py-4 text-center text-xs text-gray-400">
          MVP · Backend at{' '}
          <code className="font-mono">localhost:8080</code>
        </div>
      </footer>
    </div>
  )
}
