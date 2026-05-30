import { Link, Outlet, useNavigate } from 'react-router-dom'
import { useAuthStore } from '../store/authStore'

export function RestaurantLayout() {
  const { name, logout } = useAuthStore()
  const navigate = useNavigate()

  function handleLogout() {
    logout()
    navigate('/login')
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white shadow-sm sticky top-0 z-30">
        <div className="max-w-5xl mx-auto px-4 h-14 flex items-center justify-between">
          <Link to="/restaurant/orders" className="font-bold text-orange-500 text-lg">
            FoodDash Partner
          </Link>

          <nav className="flex items-center gap-4">
            <Link
              to="/restaurant/orders"
              className="text-sm text-gray-600 hover:text-orange-500 transition-colors"
            >
              Orders
            </Link>
            <Link
              to="/restaurant/menu"
              className="text-sm text-gray-600 hover:text-orange-500 transition-colors"
            >
              Menu
            </Link>
            <span className="text-sm text-gray-500">{name}</span>
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
    </div>
  )
}
