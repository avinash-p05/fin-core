import { useState, type FormEvent } from 'react'
import { useSession } from '../services/session'
import { Button, ErrorMessage, Field, inputClass } from '../components/ui'

export function LoginPage() {
  const { login } = useSession()
  const [username, setUsername] = useState('analyst')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await login(username, password)
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-50 px-4">
      <form onSubmit={submit} className="w-full max-w-sm space-y-4 rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <div>
          <h1 className="text-lg font-semibold text-slate-900">FinCore operations</h1>
          <p className="mt-1 text-xs text-slate-500">
            Local-development sign-in (HTTP Basic). Users and passwords are configured on the backend; see README.
          </p>
        </div>
        <Field label="Username">
          <input className={inputClass} value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
        </Field>
        <Field label="Password">
          <input className={inputClass} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
        </Field>
        {error !== undefined && <ErrorMessage error={error} />}
        <Button type="submit" className="w-full" disabled={busy || !username || !password}>
          {busy ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>
    </div>
  )
}
