import { createBrowserRouter, Navigate } from 'react-router-dom'
import { PublicLayout, RiderLayout, DriverLayout } from '@/components/layout/AppLayouts'
import { ProtectedRoute, RoleRoute, GuestRoute } from '@/routes/RouteGuards'
import { LandingPage } from '@/pages/LandingPage'
import { LoginPage } from '@/pages/LoginPage'
import { DevLoginPage } from '@/pages/DevLoginPage'
import { RiderHomePage } from '@/pages/rider/RiderHomePage'
import { ActiveTripPage } from '@/pages/rider/ActiveTripPage'
import { RateTripPage } from '@/pages/rider/RateTripPage'
import { HistoryPage } from '@/pages/rider/HistoryPage'
import { ProfilePage } from '@/pages/rider/ProfilePage'
import { DriverHomePage } from '@/pages/driver/DriverHomePage'
import { DriverTripPage } from '@/pages/driver/DriverTripPage'
import { PendingPage } from '@/pages/driver/PendingPage'
import { NotFoundPage } from '@/pages/NotFoundPage'

export const router = createBrowserRouter([
  {
    element: <PublicLayout />,
    children: [
      { index: true, element: <LandingPage /> },
      {
        element: <GuestRoute />,
        children: [
          { path: 'login', element: <LoginPage /> },
          { path: 'login/dev', element: <DevLoginPage /> },
        ],
      },
    ],
  },
  {
    element: <ProtectedRoute />,
    children: [
      {
        element: <RoleRoute role="RIDER" />,
        children: [
          {
            element: <RiderLayout />,
            children: [
              { path: 'rider', element: <RiderHomePage /> },
              { path: 'rider/trip/:tripId', element: <ActiveTripPage /> },
              { path: 'rider/trip/:tripId/rate', element: <RateTripPage /> },
              { path: 'rider/history', element: <HistoryPage /> },
              { path: 'rider/profile', element: <ProfilePage /> },
            ],
          },
        ],
      },
      {
        element: <RoleRoute role="DRIVER" />,
        children: [
          {
            element: <DriverLayout />,
            children: [
              { path: 'driver', element: <DriverHomePage /> },
              { path: 'driver/trip/:tripId', element: <DriverTripPage /> },
              { path: 'driver/pending', element: <PendingPage /> },
            ],
          },
        ],
      },
    ],
  },
  { path: '404', element: <NotFoundPage /> },
  { path: '*', element: <Navigate to="/404" replace /> },
])
