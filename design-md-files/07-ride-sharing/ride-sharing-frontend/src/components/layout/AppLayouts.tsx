import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/store/auth.store'
import { Button } from '@/components/ui/Button'

type NavItem = { to: string; label: string; icon: string }

const riderNav: NavItem[] = [
  { to: '/rider', label: 'Book', icon: '◎' },
  { to: '/rider/history', label: 'History', icon: '☰' },
  { to: '/rider/profile', label: 'Profile', icon: '◉' },
]

const driverNav: NavItem[] = [
  { to: '/driver', label: 'Home', icon: '◎' },
  { to: '/driver/pending', label: 'Pending', icon: '☰' },
]

type BottomNavProps = { items: NavItem[] }

export const BottomNav = ({ items }: BottomNavProps) => (
  <nav className="fixed bottom-0 left-0 right-0 z-40 border-t border-border bg-charcoal/95 backdrop-blur lg:hidden" aria-label="Main navigation">
    <ul className="flex justify-around px-2 py-2">
      {items.map((item) => (
        <li key={item.to} className="flex-1">
          <NavLink
            to={item.to}
            end={item.to === '/rider' || item.to === '/driver'}
            className={({ isActive }) =>
              `flex flex-col items-center gap-0.5 rounded-xl py-2 text-xs transition ${isActive ? 'text-amber-400' : 'text-muted'}`
            }
          >
            <span className="text-lg" aria-hidden="true">{item.icon}</span>
            {item.label}
          </NavLink>
        </li>
      ))}
    </ul>
  </nav>
)

export const PublicLayout = () => (
  <div className="min-h-screen bg-charcoal">
    <header className="border-b border-border px-6 py-4">
      <span className="font-display text-xl font-bold tracking-tight text-cream">
        N<span className="text-amber-500">ight</span>Ride
      </span>
    </header>
    <main className="mx-auto max-w-lg px-4 py-8">
      <Outlet />
    </main>
  </div>
)

export const RiderLayout = () => {
  const navigate = useNavigate()
  const logout = useAuthStore((s) => s.logout)

  const handleLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="min-h-screen bg-charcoal pb-20 lg:pb-0">
      <header className="sticky top-0 z-30 flex items-center justify-between border-b border-border bg-charcoal/90 px-6 py-4 backdrop-blur">
        <span className="font-display text-lg font-bold text-cream">Rider</span>
        <Button variant="ghost" className="!px-3 !py-2 text-xs" onClick={handleLogout}>
          Logout
        </Button>
      </header>
      <div className="lg:flex">
        <aside className="hidden w-56 shrink-0 border-r border-border p-4 lg:block">
          <nav className="flex flex-col gap-1" aria-label="Rider navigation">
            {riderNav.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === '/rider'}
                className={({ isActive }) =>
                  `rounded-xl px-4 py-3 text-sm font-medium transition ${isActive ? 'bg-amber-500/15 text-amber-400' : 'text-muted hover:text-cream'}`
                }
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
        </aside>
        <main className="mx-auto w-full max-w-2xl flex-1 px-4 py-6">
          <Outlet />
        </main>
      </div>
      <BottomNav items={riderNav} />
    </div>
  )
}

export const DriverLayout = () => {
  const navigate = useNavigate()
  const logout = useAuthStore((s) => s.logout)

  const handleLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="min-h-screen bg-charcoal pb-20 lg:pb-0">
      <header className="sticky top-0 z-30 flex items-center justify-between border-b border-border bg-charcoal/90 px-6 py-4 backdrop-blur">
        <span className="font-display text-lg font-bold text-cream">Driver</span>
        <Button variant="ghost" className="!px-3 !py-2 text-xs" onClick={handleLogout}>
          Logout
        </Button>
      </header>
      <div className="lg:flex">
        <aside className="hidden w-56 shrink-0 border-r border-border p-4 lg:block">
          <nav className="flex flex-col gap-1" aria-label="Driver navigation">
            {driverNav.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === '/driver'}
                className={({ isActive }) =>
                  `rounded-xl px-4 py-3 text-sm font-medium transition ${isActive ? 'bg-amber-500/15 text-amber-400' : 'text-muted hover:text-cream'}`
                }
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
        </aside>
        <main className="mx-auto w-full max-w-2xl flex-1 px-4 py-6">
          <Outlet />
        </main>
      </div>
      <BottomNav items={driverNav} />
    </div>
  )
}
