import { createBrowserRouter, Navigate } from 'react-router-dom'
import { ProtectedRoute } from './ProtectedRoute'
import { Layout } from '../components/layout/Layout'
import { LoginPage } from '../pages/LoginPage'
import { DashboardPage } from '../pages/DashboardPage'
import { SendNotificationPage } from '../pages/SendNotificationPage'
import { NotificationDetailPage } from '../pages/NotificationDetailPage'
import { InboxPage } from '../pages/InboxPage'
import { PreferencesPage } from '../pages/PreferencesPage'
import { TemplatesPage } from '../pages/TemplatesPage'
import { CreateTemplatePage } from '../pages/CreateTemplatePage'
import { UnsubscribePage } from '../pages/UnsubscribePage'

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  { path: '/unsubscribe', element: <UnsubscribePage /> },
  {
    // auth guard — redirects to /login if no user
    element: <ProtectedRoute />,
    children: [
      {
        element: <Layout />,
        children: [
          { index: true, element: <DashboardPage /> },
          { path: 'notifications/:notificationId', element: <NotificationDetailPage /> },
          // producer + admin
          {
            element: <ProtectedRoute allowedRoles={['admin', 'producer']} />,
            children: [{ path: 'send', element: <SendNotificationPage /> }],
          },
          // user + admin
          {
            element: <ProtectedRoute allowedRoles={['admin', 'user']} />,
            children: [
              { path: 'inbox', element: <InboxPage /> },
              { path: 'preferences', element: <PreferencesPage /> },
            ],
          },
          // admin only
          {
            element: <ProtectedRoute allowedRoles={['admin']} />,
            children: [
              { path: 'templates', element: <TemplatesPage /> },
              { path: 'templates/create', element: <CreateTemplatePage /> },
            ],
          },
          { path: '*', element: <Navigate to="/" replace /> },
        ],
      },
    ],
  },
])
