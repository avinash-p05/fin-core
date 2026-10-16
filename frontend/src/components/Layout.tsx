import { NavLink, Outlet } from 'react-router-dom'
import { useSession } from '../services/session'

const links = [
  { to: '/', label: 'Overview', end: true },
  { to: '/simulator', label: 'Payment Simulator' },
  { to: '/ledger', label: 'Ledger Explorer' },
  { to: '/fraud', label: 'Fraud Monitoring' },
]

export function Layout() {
  const { me, logout } = useSession()
  return (
    <div className="min-h-screen bg-slate-50">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3 px-4 py-3">
          <div className="flex items-center gap-3">
            <span className="rounded bg-indigo-600 px-2 py-1 text-sm font-bold text-white">FinCore</span>
            <span className="text-sm text-slate-500">Operations dashboard · simulated INR payments</span>
          </div>
          <div className="flex items-center gap-3 text-sm">
            <span className="text-slate-600">
              {me?.username} <span className="text-slate-400">({me?.roles.join(', ')})</span>
            </span>
            <button onClick={logout} className="text-indigo-600 hover:underline">
              Sign out
            </button>
          </div>
        </div>
        <nav className="mx-auto flex max-w-7xl gap-1 overflow-x-auto px-4">
          {links.map((l) => (
            <NavLink
              key={l.to}
              to={l.to}
              end={l.end}
              className={({ isActive }) =>
                `whitespace-nowrap border-b-2 px-3 py-2 text-sm font-medium ${
                  isActive ? 'border-indigo-600 text-indigo-700' : 'border-transparent text-slate-500 hover:text-slate-800'
                }`
              }
            >
              {l.label}
            </NavLink>
          ))}
        </nav>
      </header>
      <main className="mx-auto max-w-7xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  )
}
