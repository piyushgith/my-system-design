import { useState } from 'react'
import { Link, Outlet, useNavigate } from 'react-router-dom'
import { useAuthStore } from '../store/authStore'
import { useCartStore } from '../store/cartStore'
import { CartSidebar } from '../components/CartSidebar'

export function CustomerLayout() {
  const { name, logout } = useAuthStore()
  const { totalItems } = useCartStore()
  const navigate = useNavigate()
  const [cartOpen, setCartOpen] = useState(false)
  const count = totalItems()

  function handleLogout() {
    logout()
    navigate('/login')
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white shadow-sm sticky top-0 z-30">
        <div className="max-w-5xl mx-auto px-4 h-14 flex items-center justify-between">
          <Link to="/" className="font-bold text-orange-500 text-lg">
            FoodDash
          </Link>

          <nav className="flex items-center gap-4">
            <Link to="/orders" className="text-sm text-gray-600 hover:text-orange-500 transition-colors">
              Orders
            </Link>
            <Link to="/profile" className="text-sm text-gray-600 hover:text-orange-500 transition-colors">
              {name ?? 'Profile'}
            </Link>

            <button
              onClick={() => setCartOpen(true)}
              className="relative p-2 text-gray-600 hover:text-orange-500 transition-colors"
              aria-label="Open cart"
            >
              <svg className="w-6 h-6" fill="none" stroke="currentColor" strokeWidth={1.8} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-1.5 6h13M10 19a1 1 0 110 2 1 1 0 010-2zm6 0a1 1 0 110 2 1 1 0 010-2z" />
              </svg>
              {count > 0 && (
                <span className="absolute -top-1 -right-1 bg-orange-500 text-white text-xs w-5 h-5 rounded-full flex items-center justify-center font-semibold">
                  {count > 9 ? '9+' : count}
                </span>
              )}
            </button>

            <button
              onClick={handleLogout}
              className="text-sm text-gray-400 hover:text-red-500 transition-colors"
            >
              Logout
            </button>
          </nav>
        </div>
      </header>

      <main className="max-w-5xl mx-auto px-4 py-6">
        <Outlet />
      </main>

      <CartSidebar isOpen={cartOpen} onClose={() => setCartOpen(false)} />
    </div>
  )
}
