import { UrlHistory } from '@/features/url-shortener/UrlHistory'

export function HistoryPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-extrabold text-gray-50 font-display tracking-tight">History</h1>
        <p className="text-sm text-gray-600 mt-1 font-mono">
          Stored locally in this browser. Clears with browser data.
        </p>
      </div>
      <div className="rounded-xl border border-gray-800 bg-gray-900/50 p-6">
        <UrlHistory />
      </div>
    </div>
  )
}
