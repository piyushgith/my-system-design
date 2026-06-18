import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/Button'

export const LandingPage = () => {
  const navigate = useNavigate()

  return (
    <div className="animate-fade-in flex flex-col gap-8">
      <div className="space-y-4">
        <p className="text-xs font-semibold uppercase tracking-[0.3em] text-amber-500">Bangalore · Demo</p>
        <h1 className="font-display text-4xl font-bold leading-tight text-cream md:text-5xl">
          Move through the city
          <span className="block text-amber-500">after dark.</span>
        </h1>
        <p className="max-w-md text-muted">
          Production-grade ride-sharing frontend wired to the Spring Boot V1 API. Book as rider or drive as driver.
        </p>
      </div>

      <div className="flex flex-col gap-3 sm:flex-row">
        <Button className="flex-1 w-full" onClick={() => navigate('/login/dev')}>
          Quick demo login
        </Button>
        <Button variant="secondary" className="flex-1 w-full" onClick={() => navigate('/login')}>
          OTP login
        </Button>
      </div>

      <div className="rounded-2xl border border-border bg-surface/50 p-4 text-sm text-muted">
        <p className="font-medium text-cream">Happy path</p>
        <ol className="mt-2 list-decimal space-y-1 pl-4">
          <li>Login as driver → go online</li>
          <li>Login as rider → estimate fare → request trip</li>
          <li>Driver accepts within 15s → complete trip</li>
        </ol>
      </div>
    </div>
  )
}
