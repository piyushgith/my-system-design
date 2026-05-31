import { UrlHistory } from '@/features/url-shortener/UrlHistory'

export function HistoryPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">History</h1>
        <p className="text-sm text-gray-500 mt-1">
          URLs shortened in this browser. Stored locally — clears with browser data.
        </p>
      </div>
      <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-6">
        <UrlHistory />
      </div>
    </div>
  )
}
