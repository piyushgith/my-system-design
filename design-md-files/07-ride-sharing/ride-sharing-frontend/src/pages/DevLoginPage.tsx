import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/store/auth.store'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'

export const DevLoginPage = () => {
  const navigate = useNavigate()
  const loginDev = useAuthStore((s) => s.loginDev)

  const handleLogin = (shortcut: 'rider' | 'driver') => {
    loginDev(shortcut)
    navigate(shortcut === 'rider' ? '/rider' : '/driver')
  }

  return (
    <div className="animate-fade-in space-y-6">
      <div>
        <h1 className="font-display text-2xl font-bold text-cream">Dev login</h1>
        <p className="mt-1 text-sm text-muted">Uses seeded demo users via X-Uid headers</p>
      </div>

      <div className="grid gap-4">
        <Card title="Demo Rider" subtitle="22222222-… · MG Road → Koramangala">
          <Button className="w-full" onClick={() => handleLogin('rider')}>
            Continue as Rider
          </Button>
        </Card>
        <Card title="Demo Driver" subtitle="33333333-… · Toyota Etios KA-01">
          <Button variant="secondary" className="w-full" onClick={() => handleLogin('driver')}>
            Continue as Driver
          </Button>
        </Card>
      </div>
    </div>
  )
}
