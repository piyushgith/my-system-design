import { Navigate, Outlet } from 'react-router-dom'
import { useAuthStore } from '../store/auth'
import type { AuthUser } from '../types'

interface Props {
  allowedRoles?: Array<AuthUser['role']>
}

export function ProtectedRoute({ allowedRoles }: Props) {
  const user = useAuthStore((s) => s.user)

  if (!user) return <Navigate to="/login" replace />
  if (allowedRoles && !allowedRoles.includes(user.role)) {
    return <Navigate to="/" replace />
  }
  return <Outlet />
}
