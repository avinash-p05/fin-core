import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { SessionProvider, useSession } from './services/session'
import { Layout } from './components/Layout'
import { LoginPage } from './pages/LoginPage'
import { OverviewPage } from './pages/OverviewPage'
import { SimulatorPage } from './pages/SimulatorPage'
import { LedgerExplorerPage } from './pages/LedgerExplorerPage'
import { FraudMonitoringPage } from './pages/FraudMonitoringPage'

function Routed() {
  const { me } = useSession()
  if (!me) return <LoginPage />
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<OverviewPage />} />
        <Route path="simulator" element={<SimulatorPage />} />
        <Route path="ledger" element={<LedgerExplorerPage />} />
        <Route path="fraud" element={<FraudMonitoringPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

export function App() {
  return (
    <BrowserRouter>
      <SessionProvider>
        <Routed />
      </SessionProvider>
    </BrowserRouter>
  )
}
