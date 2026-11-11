import { useState } from 'react'
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

/** Same style for sidebar links and mobile menu links. */
function navLinkClass({ isActive }: { isActive: boolean }) {
  return `block rounded-md px-3 py-2 text-sm ${
    isActive ? 'bg-indigo-50 font-medium text-indigo-700' : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
  }`
}

export function Layout() {
  const { user, logout } = useAuth()
  const [menuOpen, setMenuOpen] = useState(false)

  return (
    <div className="flex min-h-screen bg-page">
      {/* Sidebar (desktop) */}
      <aside className="hidden w-56 shrink-0 border-r border-gray-200 bg-white md:block">
        <div className="flex items-center gap-2 px-6 py-5 text-lg font-semibold text-gray-900">
          <Logo />
          FinCore
        </div>
        <nav className="space-y-1 px-3">
          {navItems.map((item) => (
            <NavLink key={item.to} to={item.to} end className={navLinkClass}>
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>

      {/* min-w-0 lets this column shrink so wide tables scroll inside their card, not the whole page. */}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="border-b border-gray-200 bg-white">
          <div className="flex items-center justify-between px-4 py-3 md:px-6">
            {/* Mobile: logo and menu button */}
            <div className="flex items-center gap-2 font-semibold text-gray-900 md:hidden">
              <Logo />
              FinCore
            </div>
            <button
              type="button"
              onClick={() => setMenuOpen(!menuOpen)}
              className="rounded-md p-2 text-gray-600 hover:bg-gray-100 md:hidden"
              aria-label={menuOpen ? 'Close menu' : 'Open menu'}
              aria-expanded={menuOpen}
            >
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                {menuOpen ? <path d="M6 6l12 12M18 6L6 18" /> : <path d="M4 7h16M4 12h16M4 17h16" />}
              </svg>
            </button>

            {/* Desktop: signed-in user and sign out */}
            <div className="ml-auto hidden items-center gap-4 text-sm md:flex">
              <span className="text-gray-600">
                {user?.username} <span className="text-gray-400">({user?.roles.join(', ')})</span>
              </span>
              <button onClick={logout} className="text-indigo-600 hover:underline">
                Sign out
              </button>
            </div>
          </div>

          {/* Mobile menu: opens below the top bar, closes when a link is chosen */}
          {menuOpen && (
            <div className="border-t border-gray-200 px-4 pb-4 pt-2 md:hidden">
              <nav className="space-y-1">
                {navItems.map((item) => (
                  <NavLink key={item.to} to={item.to} end className={navLinkClass} onClick={() => setMenuOpen(false)}>
                    {item.label}
                  </NavLink>
                ))}
              </nav>
              <div className="mt-3 flex items-center justify-between border-t border-gray-200 pt-3 text-sm">
                <span className="text-gray-600">
                  {user?.username} <span className="text-gray-400">({user?.roles.join(', ')})</span>
                </span>
                <button onClick={logout} className="font-medium text-indigo-600">
                  Sign out
                </button>
              </div>
            </div>
          )}
        </header>

        <main className="flex-1 p-4 md:p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
