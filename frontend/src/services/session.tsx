import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, clearCredentials, hasCredentials, setCredentials } from './api'
import type { Account, Me } from '../types/api'

interface Session {
  me: Me | null
  isAnalyst: boolean
  accounts: Account[]
  accountName: (id: string | null | undefined) => string
  refreshAccounts: () => Promise<void>
  login: (username: string, password: string) => Promise<void>
  logout: () => void
}

const SessionContext = createContext<Session | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [accounts, setAccounts] = useState<Account[]>([])
  const [restoring, setRestoring] = useState(hasCredentials())

  const refreshAccounts = useCallback(async () => {
    setAccounts(await api.accounts())
  }, [])

  const load = useCallback(async () => {
    const user = await api.me()
    setMe(user)
    await refreshAccounts()
  }, [refreshAccounts])

  useEffect(() => {
    if (!hasCredentials()) return
    load()
      .catch(() => clearCredentials())
      .finally(() => setRestoring(false))
  }, [load])

  const login = useCallback(
    async (username: string, password: string) => {
      setCredentials(username, password)
      try {
        await load()
      } catch (e) {
        clearCredentials()
        throw e
      }
    },
    [load],
  )

  const logout = useCallback(() => {
    clearCredentials()
    setMe(null)
    setAccounts([])
  }, [])

  const value = useMemo<Session>(() => {
    const names = new Map(accounts.map((a) => [a.id, a.displayName]))
    return {
      me,
      isAnalyst: me?.roles.includes('ANALYST') ?? false,
      accounts,
      accountName: (id) => (id ? (names.get(id) ?? (id === '00000000-0000-0000-0000-000000000001' ? 'System funding' : id.slice(0, 8))) : '—'),
      refreshAccounts,
      login,
      logout,
    }
  }, [me, accounts, refreshAccounts, login, logout])

  if (restoring) return null
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession() {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession must be used inside SessionProvider')
  return ctx
}
