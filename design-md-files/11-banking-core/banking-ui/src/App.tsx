import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { ProtectedRoute } from './routes/ProtectedRoute'
import { AppLayout } from './components/layout/AppLayout'
import { LoginPage } from './pages/LoginPage'
import { DashboardPage } from './pages/DashboardPage'
import { CustomersPage } from './pages/customers/CustomersPage'
import { CreateCustomerPage } from './pages/customers/CreateCustomerPage'
import { CustomerDetailPage } from './pages/customers/CustomerDetailPage'
import { OpenAccountPage } from './pages/accounts/OpenAccountPage'
import { AccountDetailPage } from './pages/accounts/AccountDetailPage'
import { DepositPage } from './pages/transactions/DepositPage'
import { TransferPage } from './pages/transactions/TransferPage'

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />

        {/* Authenticated routes */}
        <Route element={<ProtectedRoute />}>
          <Route element={<AppLayout />}>
            <Route index element={<DashboardPage />} />

            {/* Customers — TELLER only for list/create; detail accessible by both */}
            <Route
              path="customers"
              element={<ProtectedRoute allowedRoles={['TELLER']} />}
            >
              <Route index element={<CustomersPage />} />
              <Route path="new" element={<CreateCustomerPage />} />
            </Route>
            <Route path="customers/:cifId" element={<CustomerDetailPage />} />

            {/* Accounts */}
            <Route
              path="accounts/new"
              element={<ProtectedRoute allowedRoles={['TELLER', 'CUSTOMER']} />}
            >
              <Route index element={<OpenAccountPage />} />
            </Route>
            <Route path="accounts/:accountId" element={<AccountDetailPage />} />

            {/* Transactions */}
            <Route
              path="transactions"
              element={<ProtectedRoute allowedRoles={['TELLER', 'CUSTOMER']} />}
            >
              <Route path="deposit" element={<DepositPage />} />
              <Route path="transfer" element={<TransferPage />} />
            </Route>

            {/* Catch-all */}
            <Route path="*" element={<Navigate to="/" replace />} />
          </Route>
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
