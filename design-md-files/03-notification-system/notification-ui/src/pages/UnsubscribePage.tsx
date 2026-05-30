import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import apiClient from '../api/axios'
import { extractErrorMessage } from '../utils/errors'
import { Spinner } from '../components/ui/Spinner'

type FetchState = 'loading' | 'success' | 'error'

export function UnsubscribePage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const [fetchState, setFetchState] = useState<FetchState>('loading')
  const [errorMsg, setErrorMsg] = useState('')

  useEffect(() => {
    if (!token) return
    apiClient
      .post(`/api/v1/preferences/unsubscribe?token=${encodeURIComponent(token)}`)
      .then(() => setFetchState('success'))
      .catch((err: unknown) => {
        setFetchState('error')
        setErrorMsg(extractErrorMessage(err))
      })
  }, [token])

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 px-4">
      <div className="w-full max-w-md rounded-lg border border-gray-200 bg-white p-8 shadow-sm text-center">
        <div className="mb-4 text-5xl">🔔</div>
        <h1 className="text-xl font-bold text-gray-900 mb-2">Notification Preferences</h1>

        {!token && (
          <div className="mt-6 space-y-3">
            <div className="text-4xl">❌</div>
            <p className="text-gray-700 font-medium">Invalid unsubscribe link</p>
            <p className="text-sm text-red-600">Missing unsubscribe token. Check the link in your notification.</p>
          </div>
        )}

        {token && fetchState === 'loading' && (
          <div className="flex flex-col items-center gap-3 mt-6">
            <Spinner size="lg" />
            <p className="text-sm text-gray-500">Processing your request…</p>
          </div>
        )}

        {token && fetchState === 'success' && (
          <div className="mt-6 space-y-3">
            <div className="text-4xl">✅</div>
            <p className="text-gray-700 font-medium">You have been unsubscribed</p>
            <p className="text-sm text-gray-500">
              You will no longer receive notifications via this channel. This action is permanent.
              Contact support if you believe this was a mistake.
            </p>
          </div>
        )}

        {token && fetchState === 'error' && (
          <div className="mt-6 space-y-3">
            <div className="text-4xl">❌</div>
            <p className="text-gray-700 font-medium">Unsubscribe failed</p>
            <p className="text-sm text-red-600">{errorMsg}</p>
            <p className="text-xs text-gray-400">The link may have expired or already been used.</p>
          </div>
        )}
      </div>
    </div>
  )
}
