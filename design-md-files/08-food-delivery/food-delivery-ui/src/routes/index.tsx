import { Navigate, Outlet } from 'react-router-dom'
import { useAuthStore } from '../store/authStore'
import type { UserRole } from '../types/auth'

interface Props {
  readonly allowedRoles: UserRole[]
}

export function ProtectedRoute({ allowedRoles }: Props) {
  const { isAuthenticated, role } = useAuthStore()

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />
  }

  if (role && !allowedRoles.includes(role)) {
    if (role === 'RESTAURANT_OWNER') return <Navigate to="/restaurant/orders" replace />
    if (role === 'DELIVERY_PARTNER') return <Navigate to="/delivery" replace />
    return <Navigate to="/" replace />
  }

  return <Outlet />
}
