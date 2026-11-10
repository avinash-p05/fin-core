import { useEffect, useState, type FormEvent } from 'react'
import { useAuth } from '../context/AuthContext'
import { ApiError, api, type DemoAccount } from '../services/api'
import { Button, Label, inputClass } from '../components/ui'
import { Logo } from '../components/Logo'

const GITHUB_URL = 'https://github.com/avinash-p05/fin-core'

const features = [
  { title: 'Double-entry ledger', text: 'Every transfer posts balanced debits and credits.' },
  { title: 'Fraud screening', text: 'Risky payments are held for analyst review.' },
  { title: 'Reliable events', text: 'Kafka events via a transactional outbox.' },
]

/** Friendly message for a failed sign-in. */
function loginErrorMessage(err: unknown) {
  if (err instanceof ApiError && err.status === 401) return 'Invalid username or password.'
  if (err instanceof ApiError && err.status === 429) return 'Too many attempts. Please wait a minute and try again.'
  return 'Could not sign in. Please try again.'
}

export function LoginPage() {
  const { login } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [demo, setDemo] = useState<DemoAccount | null>(null)

  // Ask the backend whether a public demo account is available.
  useEffect(() => {
    api
      .demoAccount()
      .then(setDemo)
      .catch(() => setDemo(null))
  }, [])

  async function signIn(user: string, pass: string) {
    setSubmitting(true)
    setError('')
    try {
      await login(user, pass)
    } catch (err) {
      setError(loginErrorMessage(err))
      setPassword('')
    } finally {
      setSubmitting(false)
    }
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    signIn(username, password)
  }

  function tryDemo() {
    if (demo?.username && demo.password) signIn(demo.username, demo.password)
  }

  return (
    <div className="flex min-h-screen bg-page">
      {/* Brand panel (hidden on small screens) */}
      <div className="hidden w-1/2 flex-col justify-between bg-gradient-to-br from-indigo-600 to-indigo-800 p-12 text-white lg:flex">
        <div className="flex items-center gap-3 text-xl font-semibold">
          <Logo size="lg" inverted />
          FinCore
        </div>

        <div>
          <h2 className="text-4xl font-semibold leading-tight">
            Payments you can trust,
            <br />
            down to the last rupee.
          </h2>
          <ul className="mt-10 space-y-5">
            {features.map((feature) => (
              <li key={feature.title} className="flex gap-3">
                <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-white/20 text-sm">✓</span>
                <div>
                  <p className="font-medium">{feature.title}</p>
                  <p className="text-sm text-indigo-100">{feature.text}</p>
                </div>
              </li>
            ))}
          </ul>
        </div>

        <p className="text-sm text-indigo-200">Spring Boot · PostgreSQL · Redis · Kafka · React</p>
      </div>

      {/* Sign-in form */}
      <div className="flex flex-1 flex-col items-center justify-center px-6 py-12">
        <div className="w-full max-w-sm">
          <div className="mb-8 flex items-center gap-3 lg:hidden">
            <Logo size="lg" />
            <span className="text-xl font-semibold text-gray-900">FinCore</span>
          </div>

          <h1 className="text-2xl font-semibold text-gray-900">Welcome back</h1>
          <p className="mt-1 text-sm text-gray-500">Sign in to the operations dashboard.</p>

          <form onSubmit={handleSubmit} className="mt-8 space-y-4">
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

            {error && <p className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}

            <Button type="submit" className="w-full" disabled={submitting}>
              {submitting ? 'Signing in...' : 'Sign in'}
            </Button>
          </form>

          {demo?.enabled && (
            <>
              <div className="my-6 flex items-center gap-3 text-xs uppercase text-gray-400">
                <span className="h-px flex-1 bg-gray-200" />
                or
                <span className="h-px flex-1 bg-gray-200" />
              </div>
              <button
                type="button"
                onClick={tryDemo}
                disabled={submitting}
                className="w-full rounded-md border border-indigo-200 bg-indigo-50 px-4 py-2 text-sm font-medium text-indigo-700 hover:bg-indigo-100 disabled:opacity-50"
              >
                Try the demo
              </button>
              <p className="mt-2 text-center text-xs text-gray-500">No sign-up needed. Demo users can send payments and view the ledger.</p>
            </>
          )}

          <p className="mt-10 text-center text-xs text-gray-400">
            Simulated money only ·{' '}
            <a href={GITHUB_URL} target="_blank" rel="noreferrer" className="text-indigo-600 hover:underline">
              View on GitHub
            </a>
          </p>
        </div>
      </div>
    </div>
  )
}
