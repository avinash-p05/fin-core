import { useState, type ReactNode } from 'react'
import { ApiError, api } from '../services/api'
import { formatINR, shortId } from '../services/format'
import type { Account, CopilotResponse, FraudAlert, SuggestedAction } from '../types/api'
import { AiBadge, Button, ErrorMessage } from './ui'
import { accountName } from './tables'

const actionStyles: Record<SuggestedAction, { label: string; className: string }> = {
  APPROVE: { label: 'Suggests approve', className: 'bg-green-100 text-green-800' },
  REJECT: { label: 'Suggests reject', className: 'bg-red-100 text-red-800' },
  ESCALATE: { label: 'Suggests escalate', className: 'bg-yellow-100 text-yellow-800' },
}

const toolLabels: Record<string, string> = {
  getPayment: 'a payment',
  getRecentPayments: 'recent payments',
  getBeneficiaryHistory: 'payee history',
  getAccountSummary: 'account details',
}

/** Backend codes that deserve a friendlier message than the raw error. */
function errorText(err: unknown): string | null {
  if (!(err instanceof ApiError)) return null
  switch (err.code) {
    case 'COPILOT_DISABLED':
      return 'The copilot is not switched on for this server.'
    case 'COPILOT_QUOTA_EXCEEDED':
      return err.message
    case 'COPILOT_TIMEOUT':
    case 'COPILOT_UNAVAILABLE':
      return `${err.message}. Nothing was suggested; review the alert as usual.`
    default:
      return null
  }
}

/**
 * AI case summary for one held payment. Advisory only: it never changes the alert, and the review
 * buttons below work exactly as before. Mount with key={alert.id} so it resets per alert.
 */
export function CopilotPanel({ alert, accounts }: { alert: FraudAlert; accounts: Account[] }) {
  const [result, setResult] = useState<CopilotResponse | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [loading, setLoading] = useState(false)

  if (alert.decision !== 'REVIEW' || !alert.allowedTransitions.length) return null

  async function ask() {
    setLoading(true)
    setError(null)
    try {
      setResult(await api.copilot(alert.id))
    } catch (err) {
      setResult(null)
      setError(err)
    } finally {
      setLoading(false)
    }
  }

  const friendly = error === null ? null : errorText(error)

  return (
    <section className="space-y-3 border-t border-gray-200 pt-4" aria-label="Copilot">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h3 className="flex items-center gap-2 font-medium text-gray-900">
            Fraud copilot <AiBadge />
          </h3>
          <p className="text-xs text-gray-500">An LLM reads this payment's history and drafts a summary. You still decide.</p>
        </div>
        {!result && (
          <Button variant="secondary" disabled={loading} onClick={ask} className="shrink-0">
            {loading ? 'Reviewing…' : 'Summarise'}
          </Button>
        )}
      </div>

      {loading && (
        <div className="flex items-center gap-2 text-xs text-gray-500" role="status">
          <span className="h-3 w-3 animate-spin rounded-full border-2 border-gray-300 border-t-indigo-600" />
          Checking payment history and verifying the answer. This can take a few seconds.
        </div>
      )}

      {friendly && <p className="rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm text-gray-700">{friendly}</p>}
      {error !== null && !friendly && <ErrorMessage error={error} />}

      {result?.outcome === 'VALIDATION_FAILED' && (
        <div className="space-y-2 rounded-md border border-yellow-200 bg-yellow-50 px-3 py-2 text-sm text-yellow-900">
          <p>
            <strong>Answer discarded.</strong> It mentioned payments or amounts that don't match the records, so it isn't
            shown. Review this alert manually.
          </p>
          {result.validationErrors.length > 0 && (
            <details className="text-xs">
              <summary className="cursor-pointer">What failed</summary>
              <ul className="mt-1 list-disc space-y-0.5 pl-4">
                {result.validationErrors.map((e) => (
                  <li key={e} className="break-words">
                    {e}
                  </li>
                ))}
              </ul>
            </details>
          )}
        </div>
      )}

      {result?.outcome === 'GROUNDED' && (
        <div className="space-y-3 text-sm">
          <div className="flex flex-wrap items-center gap-2">
            <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${actionStyles[result.suggestedAction].className}`}>
              {actionStyles[result.suggestedAction].label}
            </span>
            {result.confidence != null && (
              <span className="text-xs text-gray-500">{Math.round(result.confidence * 100)}% confidence</span>
            )}
            <span className="rounded-full bg-green-50 px-2 py-0.5 text-xs text-green-700" title="Every cited payment and amount was checked against the database">
              ✓ Verified against records
            </span>
          </div>

          <p className="leading-relaxed break-words text-gray-800">
            <WithReadableIds text={result.summary ?? ''} accounts={accounts} />
          </p>

          {result.riskFactors.length > 0 && (
            <ul className="list-disc space-y-1 pl-5 text-gray-700">
              {result.riskFactors.map((f) => (
                <li key={f} className="break-words">
                  <WithReadableIds text={f} accounts={accounts} />
                </li>
              ))}
            </ul>
          )}

          {result.citations.length > 0 && (
            <div>
              <p className="mb-1 text-xs font-medium text-gray-500">Payments cited</p>
              <ul className="divide-y divide-gray-100 rounded-md border border-gray-200">
                {result.citations.map((c) => (
                  <li key={c.paymentId} className="flex justify-between px-3 py-1.5 text-xs">
                    <span className="font-mono text-gray-600">
                      {shortId(c.paymentId)}
                      {c.paymentId === alert.paymentId && <span className="ml-1 font-sans text-gray-400">(this payment)</span>}
                    </span>
                    <span className="text-gray-900">{formatINR(c.amount)}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          <p className="text-xs text-gray-400">
            {result.toolsUsed.length > 0 && <>Looked at {describeTools(result.toolsUsed)}. </>}
            {result.cached ? 'Saved answer, no new model call.' : `${(result.latencyMs / 1000).toFixed(1)} s`}
            {result.model && <> · {result.model}</>}
          </p>
        </div>
      )}
    </section>
  )
}

function describeTools(tools: string[]) {
  return [...new Set(tools)].map((t) => toolLabels[t] ?? t).join(', ')
}

const UUID_PATTERN = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi

/** Shows full UUIDs from the model's text as account names or short ids, so the summary stays readable. */
function WithReadableIds({ text, accounts }: { text: string; accounts: Account[] }) {
  const parts: ReactNode[] = []
  let last = 0
  for (const match of text.matchAll(UUID_PATTERN)) {
    const id = match[0]
    parts.push(text.slice(last, match.index))
    const account = accounts.find((a) => a.id === id)
    parts.push(
      account ? (
        <span key={`${id}-${match.index}`} className="font-medium" title={id}>
          {accountName(accounts, id)}
        </span>
      ) : (
        <span key={`${id}-${match.index}`} className="font-mono text-xs text-gray-600" title={id}>
          {shortId(id)}
        </span>
      ),
    )
    last = match.index + id.length
  }
  parts.push(text.slice(last))
  return <>{parts}</>
}
