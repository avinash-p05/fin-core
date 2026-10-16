import { useEffect, useState } from 'react'
import { api, newIdempotencyKey, type PaymentSubmission } from '../services/api'
import { formatInr, humanize, shortId } from '../services/format'
import { useSession } from '../services/session'
import type { BalanceVerification, CreatePaymentRequest, LedgerTransaction } from '../types/api'
import { Button, Card, ErrorMessage, Field, Loading, Mono, PageHeader, inputClass } from '../components/ui'
import { DecisionBadge, PaymentStatusBadge, RuleList, VerificationBadge } from '../components/badges'
import { AccountSelect, AccountVerificationResult, LedgerEntriesTable } from '../components/domain'

interface Attempt {
  label: string
  key: string
  request: CreatePaymentRequest
  outcome: string
}

interface Details {
  ledger: LedgerTransaction[]
  verifications: BalanceVerification[]
}

export function SimulatorPage() {
  const { accounts, refreshAccounts } = useSession()
  const [sourceId, setSourceId] = useState('')
  const [destinationId, setDestinationId] = useState('')
  const [amount, setAmount] = useState('2500.00')
  const [description, setDescription] = useState('Simulated transfer')

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>()
  const [result, setResult] = useState<PaymentSubmission>()
  const [details, setDetails] = useState<Details>()
  const [detailsLoading, setDetailsLoading] = useState(false)
  const [last, setLast] = useState<{ key: string; request: CreatePaymentRequest }>()
  const [attempts, setAttempts] = useState<Attempt[]>([])
  const [refundMessage, setRefundMessage] = useState<string>()

  useEffect(() => {
    if (!sourceId && accounts.length > 0) setSourceId(accounts[0].id)
    if (!destinationId && accounts.length > 1) setDestinationId(accounts[1].id)
  }, [accounts, sourceId, destinationId])

  const source = accounts.find((a) => a.id === sourceId)

  async function loadDetails(submission: PaymentSubmission) {
    setDetailsLoading(true)
    try {
      const p = submission.payment
      const [ledger, src, dst] = await Promise.all([
        api.paymentLedger(p.id),
        api.verifyAccount(p.sourceAccountId),
        api.verifyAccount(p.destinationAccountId),
      ])
      setDetails({ ledger, verifications: [src, dst] })
    } finally {
      setDetailsLoading(false)
    }
  }

  /** Sends a real request to POST /payments; every outcome shown comes from the backend. */
  async function submit(label: string, request: CreatePaymentRequest, key = newIdempotencyKey()) {
    setBusy(true)
    setError(undefined)
    setRefundMessage(undefined)
    try {
      const submission = await api.createPayment(key, request)
      setResult(submission)
      setLast({ key, request })
      setAttempts((prev) => [
        { label, key, request, outcome: `HTTP ${submission.httpStatus}${submission.replayed ? ' (replayed)' : ''} · ${submission.payment.status}` },
        ...prev,
      ].slice(0, 12))
      await Promise.all([loadDetails(submission), refreshAccounts()])
      return submission
    } catch (e) {
      setError(e)
      const status = e instanceof Error && 'status' in e ? `HTTP ${(e as { status: number }).status}` : 'error'
      setAttempts((prev) => [{ label, key, request, outcome: `${status} · ${(e as Error).message}` }, ...prev].slice(0, 12))
      return undefined
    } finally {
      setBusy(false)
    }
  }

  const form = (overrides: Partial<CreatePaymentRequest> = {}): CreatePaymentRequest => ({
    sourceAccountId: sourceId,
    destinationAccountId: destinationId,
    amount: Number(amount),
    description: description || undefined,
    ...overrides,
  })

  /**
   * Uses the lowest-balance account as the payer so the amount stays small: a large amount would be
   * held by the HIGH_VALUE fraud rule (screening runs before the funds check) instead of failing.
   */
  async function insufficientFunds() {
    const payer = accounts.filter((a) => a.id !== destinationId).reduce((low, a) => (a.balance < low.balance ? a : low))
    setSourceId(payer.id)
    await submit('Insufficient funds', form({ sourceAccountId: payer.id, amount: Number(payer.balance) + 1000 }))
  }

  async function burst() {
    for (let i = 1; i <= 7; i++) {
      await submit(`Velocity burst ${i}/7`, form({ amount: 10, description: `Velocity burst ${i}` }))
    }
  }

  async function refund() {
    if (!result) return
    setBusy(true)
    setError(undefined)
    try {
      const { refund } = await api.refund(result.payment.id, newIdempotencyKey(), { reason: 'Simulator refund' })
      setRefundMessage(`Refund ${shortId(refund.id)} of ${formatInr(refund.amount)} posted; payment is now ${humanize(refund.paymentStatus)}.`)
      const payment = await api.payment(result.payment.id)
      const updated = { ...result, payment }
      setResult(updated)
      await Promise.all([loadDetails(updated), refreshAccounts()])
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  const canSubmit = !busy && sourceId && destinationId && sourceId !== destinationId && Number(amount) > 0
  const payment = result?.payment

  return (
    <>
      <PageHeader title="Payment Simulator" description="Every button calls the real payments API; outcomes are decided by the backend." />
      {accounts.length < 2 ? (
        <Card>
          <p className="text-sm text-slate-600">
            At least two customer accounts are needed. Start the backend with <code>FINCORE_DEMO_SEED=true</code> or create accounts via
            the API (see README).
          </p>
        </Card>
      ) : (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-5">
          <div className="space-y-6 lg:col-span-2">
            <Card title="New payment">
              <div className="space-y-3">
                <Field label="Source account">
                  <AccountSelect value={sourceId} onChange={setSourceId} />
                </Field>
                <Field label="Destination account">
                  <AccountSelect value={destinationId} onChange={setDestinationId} exclude={sourceId} />
                </Field>
                <Field label="Amount (INR)" hint={source && `Source balance: ${formatInr(source.balance)}`}>
                  <input className={inputClass} type="number" min="0.01" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} />
                </Field>
                <Field label="Description">
                  <input className={inputClass} value={description} maxLength={500} onChange={(e) => setDescription(e.target.value)} />
                </Field>
                <Button className="w-full" disabled={!canSubmit} onClick={() => submit('Manual payment', form())}>
                  {busy ? 'Submitting…' : 'Simulate Payment'}
                </Button>
              </div>
            </Card>

            <Card title="Demonstrations">
              <div className="grid grid-cols-1 gap-2 text-left sm:grid-cols-2">
                <Button
                  variant="secondary"
                  disabled={!canSubmit}
                  onClick={insufficientFunds}
                  title="Sends ₹1,000 more than the lowest-balance account holds; the backend rejects it under the account lock"
                >
                  Insufficient funds
                </Button>
                <Button
                  variant="secondary"
                  disabled={busy || !last}
                  onClick={() => last && submit('Duplicate (same key + payload)', last.request, last.key)}
                  title="Re-sends the previous request with the same Idempotency-Key"
                >
                  Duplicate request
                </Button>
                <Button
                  variant="secondary"
                  disabled={busy || !last}
                  onClick={() => last && submit('Same key, different amount', { ...last.request, amount: last.request.amount + 1 }, last.key)}
                  title="Reuses the previous Idempotency-Key with a different payload; expect HTTP 409"
                >
                  Key reuse conflict
                </Button>
                <Button variant="secondary" disabled={!canSubmit} onClick={() => submit('High value', form({ amount: 120000 }))} title="₹1,20,000 triggers HIGH_VALUE">
                  High value (₹1.2L)
                </Button>
                <Button variant="secondary" disabled={!canSubmit} onClick={() => submit('Very high value', form({ amount: 150000 }))} title="Declined when combined with NEW_BENEFICIARY">
                  High value (₹1.5L)
                </Button>
                <Button variant="secondary" disabled={!canSubmit} onClick={burst} title="7 quick ₹10 payments; default limit is 5 per minute per source">
                  Velocity burst (7×)
                </Button>
              </div>
              <p className="mt-3 text-xs text-slate-500">
                Fraud outcomes depend on configuration and history (e.g. a high-value payment to a <em>new</em> beneficiary scores higher than to a
                known one). The decision shown is the backend's.
              </p>
            </Card>

            <Card title="Recent attempts">
              {attempts.length === 0 ? (
                <p className="text-sm text-slate-500">Nothing submitted yet.</p>
              ) : (
                <ul className="space-y-2 text-xs">
                  {attempts.map((a, i) => (
                    <li key={i} className="rounded border border-slate-100 p-2">
                      <div className="font-medium text-slate-700">{a.label}</div>
                      <div className="text-slate-500">
                        {formatInr(a.request.amount)} · key <Mono>{a.key.slice(0, 15)}…</Mono>
                      </div>
                      <div className="text-slate-600">{a.outcome}</div>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
          </div>

          <div className="space-y-6 lg:col-span-3">
            {error !== undefined && <ErrorMessage error={error} />}
            {!payment && !error && (
              <Card>
                <p className="text-sm text-slate-500">Submit a payment to see its status, fraud assessment, ledger entries and verification.</p>
              </Card>
            )}
            {payment && (
              <Card
                title="Result"
                actions={
                  (payment.status === 'COMPLETED' || payment.status === 'PARTIALLY_REFUNDED') && (
                    <Button variant="secondary" disabled={busy} onClick={refund}>
                      Refund remaining
                    </Button>
                  )
                }
              >
                <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
                  <div>
                    <dt className="text-xs text-slate-500">Payment ID</dt>
                    <dd>
                      <Mono>{payment.id}</Mono>
                    </dd>
                  </div>
                  <div>
                    <dt className="text-xs text-slate-500">Status</dt>
                    <dd className="flex items-center gap-2">
                      <PaymentStatusBadge status={payment.status} />
                      {payment.failureReason && <span className="text-xs text-slate-500">{payment.failureReason}</span>}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-xs text-slate-500">Amount</dt>
                    <dd className="font-medium">
                      {formatInr(payment.amount)}
                      {payment.refundedAmount > 0 && <span className="ml-2 text-xs text-violet-700">refunded {formatInr(payment.refundedAmount)}</span>}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-xs text-slate-500">Idempotency</dt>
                    <dd className="text-xs">
                      {result?.replayed ? 'Replayed: original result returned, no new transfer' : 'New request executed'} (HTTP {result?.httpStatus})
                    </dd>
                  </div>
                  <div>
                    <dt className="text-xs text-slate-500">Fraud decision</dt>
                    <dd className="flex items-center gap-2">
                      <DecisionBadge decision={payment.fraud?.decision} />
                      {payment.fraud && <span className="text-xs text-slate-500">risk score {payment.fraud.score}</span>}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-xs text-slate-500">Triggered rules</dt>
                    <dd>
                      <RuleList rules={payment.fraud?.triggeredRules ?? []} />
                    </dd>
                  </div>
                </dl>
                {refundMessage && <p className="mt-3 text-sm text-violet-700">{refundMessage}</p>}
              </Card>
            )}
            {payment && (
              <Card title="Ledger entries for this payment">
                {detailsLoading && !details ? (
                  <Loading />
                ) : details && details.ledger.length > 0 ? (
                  <div className="space-y-4">
                    {details.ledger.map((tx) => (
                      <div key={tx.id}>
                        <div className="mb-2 flex flex-wrap items-center gap-2 text-xs text-slate-500">
                          <span className="font-medium text-slate-700">{tx.type}</span>
                          <span>debits {formatInr(tx.totalDebits)}</span>
                          <span>credits {formatInr(tx.totalCredits)}</span>
                          <VerificationBadge status={tx.balanced ? 'VALID' : 'INVALID'} />
                        </div>
                        <LedgerEntriesTable entries={tx.entries} showPayment={false} />
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="text-sm text-slate-500">No ledger transaction was posted: no money moved for this payment.</p>
                )}
              </Card>
            )}
            {payment && details && (
              <Card title="Ledger verification (both accounts)">
                <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                  {details.verifications.map((v) => (
                    <AccountVerificationResult key={v.accountId} result={v} />
                  ))}
                </div>
              </Card>
            )}
          </div>
        </div>
      )}
    </>
  )
}
