import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { Logo } from './Logo'

const navItems = [
  { to: '/', label: 'Dashboard' },
  { to: '/accounts', label: 'Accounts' },
  { to: '/payments', label: 'Transactions' },
  { to: '/payments/new', label: 'New Payment' },
  { to: '/ledger', label: 'Ledger' },
  { to: '/fraud', label: 'Fraud Alerts' },
]

export function Layout() {
  const { user, logout } = useAuth()

  return (
    <div className="flex min-h-screen bg-page">
      {/* Sidebar */}
      <aside className="hidden w-56 shrink-0 bg-slate-900 text-slate-300 md:block">
        <div className="flex items-center gap-2 px-6 py-5 text-lg font-semibold text-white">
          <Logo />
          FinCore
        </div>
        <nav className="space-y-1 px-3">
          {navItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end
              className={({ isActive }) =>
                `block rounded-md px-3 py-2 text-sm ${isActive ? 'bg-indigo-600 text-white' : 'hover:bg-slate-800 hover:text-white'}`
              }
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>

      {/* min-w-0 lets this column shrink so wide tables scroll inside their card, not the whole page. */}
      <div className="flex min-w-0 flex-1 flex-col">
        {/* Top bar */}
        <header className="flex items-center justify-between border-b border-gray-200 bg-white px-6 py-3">
          {/* On small screens the sidebar is hidden, so show the links here instead. */}
          <nav className="flex gap-3 overflow-x-auto text-sm md:hidden">
            {navItems.map((item) => (
              <NavLink key={item.to} to={item.to} end className={({ isActive }) => (isActive ? 'font-medium text-indigo-600' : 'text-gray-600')}>
                {item.label}
              </NavLink>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-4 text-sm">
            <span className="text-gray-600">
              {user?.username} <span className="text-gray-400">({user?.roles.join(', ')})</span>
            </span>
            <button onClick={logout} className="text-indigo-600 hover:underline">
              Sign out
            </button>
          </div>
        </header>

        <main className="flex-1 p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
