import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { ProtectedRoute } from './routes'
import { LoginPage } from './pages/LoginPage'
import { CustomerLayout } from './layouts/CustomerLayout'
import { RestaurantLayout } from './layouts/RestaurantLayout'
import { DeliveryLayout } from './layouts/DeliveryLayout'
import { HomePage } from './pages/customer/HomePage'
import { RestaurantPage } from './pages/customer/RestaurantPage'
import { OrdersPage } from './pages/customer/OrdersPage'
import { OrderDetailPage } from './pages/customer/OrderDetailPage'
import { ProfilePage } from './pages/customer/ProfilePage'
import { RestaurantOrdersPage } from './pages/restaurant/OrdersPage'
import { RestaurantMenuPage } from './pages/restaurant/MenuPage'
import { DeliveryDashboardPage } from './pages/delivery/DashboardPage'
import { ToastContainer } from './components/ui/ToastContainer'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />

        <Route element={<ProtectedRoute allowedRoles={['CUSTOMER']} />}>
          <Route element={<CustomerLayout />}>
            <Route path="/" element={<HomePage />} />
            <Route path="/restaurants/:restaurantId" element={<RestaurantPage />} />
            <Route path="/orders" element={<OrdersPage />} />
            <Route path="/orders/:orderId" element={<OrderDetailPage />} />
            <Route path="/profile" element={<ProfilePage />} />
          </Route>
        </Route>

        <Route element={<ProtectedRoute allowedRoles={['RESTAURANT_OWNER']} />}>
          <Route element={<RestaurantLayout />}>
            <Route path="/restaurant/orders" element={<RestaurantOrdersPage />} />
            <Route path="/restaurant/menu" element={<RestaurantMenuPage />} />
          </Route>
        </Route>

        <Route element={<ProtectedRoute allowedRoles={['DELIVERY_PARTNER']} />}>
          <Route element={<DeliveryLayout />}>
            <Route path="/delivery" element={<DeliveryDashboardPage />} />
          </Route>
        </Route>

        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      <ToastContainer />
    </BrowserRouter>
  )
}
