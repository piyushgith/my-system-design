import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '../store/authStore'
import { apiClient } from '../api/axios'
import { Button } from '../components/ui/Button'
import { Input } from '../components/ui/Input'
import { ErrorAlert } from '../components/ui/ErrorAlert'
import type { UserRole } from '../types'

export function LoginPage() {
  const navigate = useNavigate()
  const login = useAuthStore((s) => s.login)

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)

    try {
      const authHeader = 'Basic ' + btoa(`${username}:${password}`)
      // Verify credentials by hitting a protected endpoint
      const res = await apiClient.get(`/customers/${username}`, {
        headers: { Authorization: authHeader },
        validateStatus: () => true,
      })

      if (res.status === 401) {
        setError('Invalid credentials')
        return
      }

      const role: UserRole = username === 'teller' ? 'TELLER' : 'CUSTOMER'
      login(username, password, role)
      navigate('/')
    } catch {
      setError('Login failed. Check credentials and try again.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-100">
      <div className="w-full max-w-sm">
        <div className="mb-8 text-center">
          <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-xl bg-blue-600 text-white text-xl font-bold">
            B
          </div>
          <h1 className="text-2xl font-bold text-slate-900">Banking Core</h1>
          <p className="mt-1 text-sm text-slate-500">Sign in to your account</p>
        </div>

        <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          {error && <div className="mb-4"><ErrorAlert message={error} onDismiss={() => setError('')} /></div>}

          <form onSubmit={handleSubmit} className="space-y-4">
            <Input
              label="Username"
              placeholder="teller or CIF-XXXXXXXX"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
              autoComplete="username"
            />
            <Input
              label="Password"
              type="password"
              placeholder="Enter password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              autoComplete="current-password"
            />
            <Button type="submit" loading={loading} className="w-full" size="lg">
              Sign In
            </Button>
          </form>
        </div>

        <div className="mt-4 rounded-lg bg-blue-50 border border-blue-200 p-3 text-xs text-blue-700">
          <p className="font-semibold mb-1">Dev credentials</p>
          <p>Teller: <code className="font-mono">teller / teller</code></p>
          <p>Customer: <code className="font-mono">{'{cifId}'} / customer</code></p>
        </div>
      </div>
    </div>
  )
}
