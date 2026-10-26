import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, useAuth } from './context/AuthContext'
import { Layout } from './components/Layout'
import { LoginPage } from './pages/LoginPage'
import { DashboardPage } from './pages/DashboardPage'
import { AccountsPage } from './pages/AccountsPage'
import { PaymentsPage } from './pages/PaymentsPage'
import { NewPaymentPage } from './pages/NewPaymentPage'
import { LedgerPage } from './pages/LedgerPage'
import { FraudAlertsPage } from './pages/FraudAlertsPage'

function AppRoutes() {
  const { user } = useAuth()

  // Not signed in: show only the login page.
  if (!user) return <LoginPage />

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/accounts" element={<AccountsPage />} />
        <Route path="/payments" element={<PaymentsPage />} />
        <Route path="/payments/new" element={<NewPaymentPage />} />
        <Route path="/ledger" element={<LedgerPage />} />
        <Route path="/fraud" element={<FraudAlertsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </BrowserRouter>
  )
}
