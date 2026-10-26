import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, clearCredentials, hasCredentials, setCredentials } from '../services/api'
import type { Me } from '../types/api'

interface AuthContextValue {
  user: Me | null
  isAnalyst: boolean
  login: (username: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Me | null>(null)
  const [checking, setChecking] = useState(hasCredentials())

  // If credentials are already stored for this tab, check them once on startup.
  useEffect(() => {
    if (!hasCredentials()) return
    api
      .me()
      .then(setUser)
      .catch(() => clearCredentials())
      .finally(() => setChecking(false))
  }, [])

  async function login(username: string, password: string) {
    setCredentials(username, password)
    try {
      setUser(await api.me())
    } catch (err) {
      clearCredentials()
      throw err
    }
  }

  function logout() {
    clearCredentials()
    setUser(null)
  }

  if (checking) return null

  const value: AuthContextValue = {
    user,
    isAnalyst: user?.roles.includes('ANALYST') ?? false,
    login,
    logout,
  }
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside AuthProvider')
  return context
}
