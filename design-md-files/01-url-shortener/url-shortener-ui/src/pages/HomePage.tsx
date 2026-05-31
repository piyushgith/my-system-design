import { useState } from 'react'
import { ShortenForm } from '@/features/url-shortener/ShortenForm'
import { UrlResult } from '@/features/url-shortener/UrlResult'
import type { CreateUrlResponse } from '@/types/api'

export function HomePage() {
  const [result, setResult] = useState<CreateUrlResponse | null>(null)

  return (
    <div className="flex flex-col gap-8">
      {/* Hero */}
      <div className="text-center pt-4">
        <h1 className="text-3xl font-bold text-gray-900 mb-2">Shorten any URL</h1>
        <p className="text-gray-500 text-base">
          Paste a long link, get a compact shareable URL. Optionally set a custom alias or expiry.
        </p>
      </div>

      {/* Form card */}
      <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-6">
        <ShortenForm onSuccess={setResult} />
      </div>

      {/* Result */}
      {result && (
        <UrlResult result={result} onDismiss={() => setResult(null)} />
      )}

      {/* How it works */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mt-2">
        {[
          {
            icon: '🔗',
            title: 'Paste your URL',
            desc: 'Any valid http:// or https:// link up to 2048 characters.',
          },
          {
            icon: '⚡',
            title: 'Get a short link',
            desc: '7-character code generated instantly. Custom alias supported.',
          },
          {
            icon: '📋',
            title: 'Copy & share',
            desc: 'One click to copy. Link redirects in under 10 ms with Redis caching.',
          },
        ].map((item) => (
          <div
            key={item.title}
            className="rounded-xl border border-gray-200 bg-white p-4 text-center hover:border-brand-200 transition-colors"
          >
            <div className="text-2xl mb-2">{item.icon}</div>
            <h3 className="text-sm font-semibold text-gray-900 mb-1">{item.title}</h3>
            <p className="text-xs text-gray-500 leading-relaxed">{item.desc}</p>
          </div>
        ))}
      </div>
    </div>
  )
}
