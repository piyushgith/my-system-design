import { Navigate, Outlet } from 'react-router-dom'
import { useAuthStore } from '@/store/auth.store'

export const ProtectedRoute = () => {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated())

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />
  }

  return <Outlet />
}

export const RoleRoute = ({ role }: { role: 'RIDER' | 'DRIVER' }) => {
  const userRole = useAuthStore((s) => s.role)

  if (userRole !== role) {
    return <Navigate to={userRole === 'DRIVER' ? '/driver' : '/rider'} replace />
  }

  return <Outlet />
}

export const GuestRoute = () => {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated())
  const role = useAuthStore((s) => s.role)

  if (isAuthenticated) {
    return <Navigate to={role === 'DRIVER' ? '/driver' : '/rider'} replace />
  }

  return <Outlet />
}
