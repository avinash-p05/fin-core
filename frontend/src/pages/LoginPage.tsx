import { useState, type FormEvent } from 'react'
import { useAuth } from '../context/AuthContext'
import { Button, ErrorMessage, Label, inputClass } from '../components/ui'

export function LoginPage() {
  const { login } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await login(username, password)
    } catch (err) {
      setError(err)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-100 px-4">
      <form onSubmit={handleSubmit} className="w-full max-w-sm space-y-4 rounded-lg border border-gray-200 bg-white p-8 shadow-sm">
        <h1 className="text-center text-2xl font-semibold text-gray-900">FinCore</h1>
        <p className="text-center text-sm text-gray-500">Sign in to continue</p>

        <Label text="Username">
          <input className={inputClass} value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required />
        </Label>
        <Label text="Password">
          <input
            className={inputClass}
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </Label>

        {error !== null && <ErrorMessage error={error} />}

        <Button type="submit" className="w-full" disabled={submitting}>
          {submitting ? 'Signing in...' : 'Sign in'}
        </Button>
      </form>
    </div>
  )
}
