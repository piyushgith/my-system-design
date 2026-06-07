import { useState } from 'react'
import { ShortenForm } from '@/features/url-shortener/ShortenForm'
import { UrlResult } from '@/features/url-shortener/UrlResult'
import type { CreateUrlResponse } from '@/types/api'

export function HomePage() {
  const [result, setResult] = useState<CreateUrlResponse | null>(null)

  return (
    <div className="flex flex-col gap-8">
      {/* Hero */}
      <div className="pt-4 pb-2">
        <div className="inline-flex items-center gap-2 mb-5 px-3 py-1 rounded-full border border-brand-300/20 bg-brand-300/5 text-brand-300 text-xs font-mono tracking-widest">
          <span className="h-1.5 w-1.5 rounded-full bg-brand-300 animate-pulse" aria-hidden="true" />
          <span>LINK COMPRESSOR</span>
        </div>
        <h1 className="text-4xl sm:text-5xl font-extrabold text-gray-50 mb-3 font-display tracking-tight leading-[1.1]">
          Long URLs,{' '}
          <span
            className="text-brand-300"
            style={{ textShadow: '0 0 40px rgba(200,255,87,0.3)' }}
          >
            instantly
          </span>{' '}
          short.
        </h1>
        <p className="text-gray-500 text-sm max-w-md leading-relaxed">
          Paste any link, get a compact shareable URL. Optional custom alias and expiry.
          Redirects under 10&thinsp;ms via Redis cache.
        </p>
      </div>

      {/* Form card */}
      <div className="rounded-xl border border-gray-800 bg-gray-900/50 backdrop-blur-sm p-6">
        <ShortenForm onSuccess={setResult} />
      </div>

      {/* Result */}
      {result && (
        <UrlResult result={result} onDismiss={() => setResult(null)} />
      )}

      {/* How it works */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
        {[
          {
            step: '01',
            title: 'Paste your URL',
            desc: 'Any valid https:// link up to 2048 characters.',
            icon: (
              <svg className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.5" viewBox="0 0 24 24" aria-hidden="true">
                <path strokeLinecap="round" strokeLinejoin="round" d="M13.19 8.688a4.5 4.5 0 011.242 7.244l-4.5 4.5a4.5 4.5 0 01-6.364-6.364l1.757-1.757m13.35-.622l1.757-1.757a4.5 4.5 0 00-6.364-6.364l-4.5 4.5a4.5 4.5 0 001.242 7.244" />
              </svg>
            ),
          },
          {
            step: '02',
            title: 'Get a short link',
            desc: '7-character code generated instantly. Custom alias supported.',
            icon: (
              <svg className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.5" viewBox="0 0 24 24" aria-hidden="true">
                <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 13.5l10.5-11.25L12 10.5h8.25L9.75 21.75 12 13.5H3.75z" />
              </svg>
            ),
          },
          {
            step: '03',
            title: 'Copy & share',
            desc: 'One click to copy. Redirect resolves under 10 ms with Redis.',
            icon: (
              <svg className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.5" viewBox="0 0 24 24" aria-hidden="true">
                <path strokeLinecap="round" strokeLinejoin="round" d="M7.217 10.907a2.25 2.25 0 100 2.186m0-2.186c.18.324.283.696.283 1.093s-.103.77-.283 1.093m0-2.186l9.566-5.314m-9.566 7.5l9.566 5.314m0 0a2.25 2.25 0 103.935 2.186 2.25 2.25 0 00-3.935-2.186zm0-12.814a2.25 2.25 0 103.933-2.185 2.25 2.25 0 00-3.933 2.185z" />
              </svg>
            ),
          },
        ].map((item) => (
          <div
            key={item.title}
            className="rounded-lg border border-gray-800 bg-gray-900/30 p-4 group card-hover"
          >
            <div className="flex items-center justify-between mb-3">
              <span className="text-xs font-mono text-brand-300/40 group-hover:text-brand-300/70 transition-colors">
                {item.step}
              </span>
              <span className="text-gray-700 group-hover:text-brand-300/60 transition-colors">
                {item.icon}
              </span>
            </div>
            <h3 className="text-sm font-bold text-gray-300 mb-1 font-display">{item.title}</h3>
            <p className="text-xs text-gray-600 leading-relaxed">{item.desc}</p>
          </div>
        ))}
      </div>

      {/* Stats */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        {[
          { value: '< 10ms', label: 'redirect speed' },
          { value: '7 chars', label: 'default slug' },
          { value: '99.9%', label: 'uptime target' },
          { value: '2048', label: 'max URL length' },
        ].map((stat) => (
          <div
            key={stat.label}
            className="rounded-lg border border-gray-800/60 bg-gray-900/20 p-4 text-center"
          >
            <div className="text-lg font-extrabold text-brand-300 font-display tracking-tight">
              {stat.value}
            </div>
            <div className="text-xs text-gray-600 font-mono mt-0.5">{stat.label}</div>
          </div>
        ))}
      </div>
    </div>
  )
}
