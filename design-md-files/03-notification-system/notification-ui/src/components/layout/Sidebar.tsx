import { NavLink } from 'react-router-dom'
import { useAuthStore } from '../../store/auth'

interface NavItem {
  to: string
  label: string
  icon: string
  roles: Array<'admin' | 'producer' | 'user'>
}

const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Dashboard', icon: '📊', roles: ['admin', 'producer', 'user'] },
  { to: '/send', label: 'Send Notification', icon: '📤', roles: ['admin', 'producer'] },
  { to: '/inbox', label: 'Inbox', icon: '📥', roles: ['admin', 'user'] },
  { to: '/preferences', label: 'Preferences', icon: '⚙️', roles: ['admin', 'user'] },
  { to: '/templates', label: 'Templates', icon: '📄', roles: ['admin'] },
]

export function Sidebar() {
  const { user } = useAuthStore()
  if (!user) return null

  const visibleItems = NAV_ITEMS.filter((item) => item.roles.includes(user.role))

  return (
    <nav className="w-56 shrink-0 border-r border-gray-200 bg-white">
      <ul className="space-y-1 p-3">
        {visibleItems.map((item) => (
          <li key={item.to}>
            <NavLink
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) =>
                `flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors
                ${isActive
                  ? 'bg-blue-50 text-blue-700'
                  : 'text-gray-700 hover:bg-gray-100'}`
              }
            >
              <span>{item.icon}</span>
              {item.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}
