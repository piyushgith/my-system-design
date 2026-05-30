import { NavLink, useNavigate } from 'react-router-dom'
import { useAuthStore } from '../../store/authStore'

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  `flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
    isActive
      ? 'bg-white/10 text-white'
      : 'text-slate-300 hover:bg-white/5 hover:text-white'
  }`

export function Sidebar() {
  const { role, username, logout } = useAuthStore()
  const navigate = useNavigate()
  const isTeller = role === 'TELLER'

  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  return (
    <aside className="flex h-screen w-56 flex-col bg-slate-800">
      <div className="border-b border-slate-700 p-5">
        <div className="flex items-center gap-2">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-blue-500 text-white font-bold text-sm">
            B
          </div>
          <div>
            <p className="text-sm font-semibold text-white">Banking Core</p>
            <p className="text-xs text-slate-400">{isTeller ? 'Teller Portal' : 'Customer Portal'}</p>
          </div>
        </div>
      </div>

      <nav className="flex-1 space-y-1 overflow-y-auto p-3">
        <NavLink to="/" end className={navLinkClass}>
          <HomeIcon />
          Dashboard
        </NavLink>

        {isTeller && (
          <>
            <p className="px-3 pt-4 pb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
              Management
            </p>
            <NavLink to="/customers" className={navLinkClass}>
              <UsersIcon />
              Customers
            </NavLink>
            <NavLink to="/customers/new" className={navLinkClass}>
              <UserPlusIcon />
              New Customer
            </NavLink>
            <NavLink to="/accounts/new" className={navLinkClass}>
              <CreditCardIcon />
              Open Account
            </NavLink>
          </>
        )}

        <p className="px-3 pt-4 pb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
          Transactions
        </p>
        <NavLink to="/transactions/deposit" className={navLinkClass}>
          <ArrowDownIcon />
          Deposit
        </NavLink>
        <NavLink to="/transactions/transfer" className={navLinkClass}>
          <ArrowsIcon />
          Transfer
        </NavLink>
      </nav>

      <div className="border-t border-slate-700 p-3">
        <div className="mb-2 rounded-lg bg-slate-700 px-3 py-2">
          <p className="text-xs text-slate-400">Logged in as</p>
          <p className="text-sm font-medium text-white">{username}</p>
          <p className="text-xs text-blue-400">{role}</p>
        </div>
        <button
          onClick={handleLogout}
          className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-sm text-slate-300 hover:bg-white/5 hover:text-white transition-colors"
        >
          <LogoutIcon />
          Sign Out
        </button>
      </div>
    </aside>
  )
}

function HomeIcon() {
  return <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6" /></svg>
}
function UsersIcon() {
  return <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197" /></svg>
}
function UserPlusIcon() {
  return <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z" /></svg>
}
function CreditCardIcon() {
  return <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 10h18M7 15h1m4 0h1m-7 4h12a3 3 0 003-3V8a3 3 0 00-3-3H6a3 3 0 00-3 3v8a3 3 0 003 3z" /></svg>
}
function ArrowDownIcon() {
  return <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m0 0l-4-4m4 4l4-4" /></svg>
}
function ArrowsIcon() {
  return <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4" /></svg>
}
function LogoutIcon() {
  return <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" /></svg>
}
