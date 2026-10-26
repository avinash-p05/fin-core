import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { api, newIdempotencyKey, type PaymentSubmission } from '../services/api'
import { formatINR } from '../services/format'
import { useFetch } from '../hooks/useFetch'
import type { BalanceVerification, CreatePaymentRequest, LedgerTransaction } from '../types/api'
import { Button, Card, ErrorMessage, Label, PageHeader, Spinner, inputClass } from '../components/ui'
import { StatusBadge } from '../components/StatusBadge'
import { LedgerTable, accountName } from '../components/tables'

export function NewPaymentPage() {
  const accounts = useFetch(() => api.accounts())

  // Form fields
  const [fromId, setFromId] = useState('')
  const [toId, setToId] = useState('')
  const [amount, setAmount] = useState('2500')
  const [description, setDescription] = useState('')

  // Result of the last request
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [result, setResult] = useState<PaymentSubmission | null>(null)
  const [ledger, setLedger] = useState<LedgerTransaction[]>([])
  const [checks, setChecks] = useState<BalanceVerification[]>([])

  // The last request and its idempotency key, so we can send an exact duplicate.
  const [lastRequest, setLastRequest] = useState<{ key: string; body: CreatePaymentRequest } | null>(null)

  const accountList = accounts.data ?? []

  // Pick the first two accounts by default once they have loaded.
  useEffect(() => {
    const list = accounts.data
    if (list && list.length >= 2 && !fromId && !toId) {
      setFromId(list[0].id)
      setToId(list[1].id)
    }
  }, [accounts.data, fromId, toId])

  async function send(body: CreatePaymentRequest, key = newIdempotencyKey()) {
    setSubmitting(true)
    setError(null)
    try {
      const submission = await api.createPayment(key, body)
      setResult(submission)
      setLastRequest({ key, body })

      // Load the ledger entries and the balance checks for both accounts.
      const payment = submission.payment
      const [entries, fromCheck, toCheck] = await Promise.all([
        api.paymentLedger(payment.id),
        api.verifyAccount(payment.sourceAccountId),
        api.verifyAccount(payment.destinationAccountId),
      ])
      setLedger(entries)
      setChecks([fromCheck, toCheck])
      accounts.reload() // balances changed
    } catch (err) {
      setError(err)
    } finally {
      setSubmitting(false)
    }
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    send({ sourceAccountId: fromId, destinationAccountId: toId, amount: Number(amount), description: description || undefined })
  }

  // --- Test scenarios: each one sends a real request to the backend ---

  function sendMoreThanBalance() {
    // Use the account with the lowest balance so the amount stays small.
    const payer = accountList.filter((a) => a.id !== toId).reduce((low, a) => (a.balance < low.balance ? a : low))
    setFromId(payer.id)
    send({ sourceAccountId: payer.id, destinationAccountId: toId, amount: payer.balance + 1000 })
  }

  function sendDuplicate() {
    if (lastRequest) send(lastRequest.body, lastRequest.key)
  }

  function sendKeyConflict() {
    if (lastRequest) send({ ...lastRequest.body, amount: lastRequest.body.amount + 1 }, lastRequest.key)
  }

  function sendHighValue() {
    send({ sourceAccountId: fromId, destinationAccountId: toId, amount: 120000 })
  }

  async function sendBurst() {
    for (let i = 0; i < 7; i++) {
      await send({ sourceAccountId: fromId, destinationAccountId: toId, amount: 10 })
    }
  }

  async function refund() {
    if (!result) return
    setSubmitting(true)
    setError(null)
    try {
      await api.refund(result.payment.id, newIdempotencyKey(), {})
      const payment = await api.payment(result.payment.id)
      setResult({ ...result, payment })
      setLedger(await api.paymentLedger(payment.id))
      accounts.reload()
    } catch (err) {
      setError(err)
    } finally {
      setSubmitting(false)
    }
  }

  if (accounts.loading && !accounts.data) return <Spinner />

  const payment = result?.payment
  const canRefund = payment?.status === 'COMPLETED' || payment?.status === 'PARTIALLY_REFUNDED'

  return (
    <div>
      <PageHeader title="New Payment" />

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        {/* Left column: form and test scenarios */}
        <div className="space-y-6">
          <Card title="Payment details">
            <form onSubmit={handleSubmit} className="space-y-4">
              <Label text="From account">
                <select className={inputClass} value={fromId} onChange={(e) => setFromId(e.target.value)}>
                  {accountList.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.displayName} ({formatINR(a.balance)})
                    </option>
                  ))}
                </select>
              </Label>
              <Label text="To account">
                <select className={inputClass} value={toId} onChange={(e) => setToId(e.target.value)}>
                  {accountList
                    .filter((a) => a.id !== fromId)
                    .map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.displayName}
                      </option>
                    ))}
                </select>
              </Label>
              <Label text="Amount (INR)">
                <input className={inputClass} type="number" min="0.01" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} required />
              </Label>
              <Label text="Description">
                <input className={inputClass} value={description} maxLength={500} onChange={(e) => setDescription(e.target.value)} />
              </Label>
              <Button type="submit" className="w-full" disabled={submitting || !fromId || !toId}>
                {submitting ? 'Sending...' : 'Send payment'}
              </Button>
            </form>
          </Card>

          <Card title="Test scenarios">
            <div className="grid grid-cols-2 gap-2">
              <Button variant="secondary" disabled={submitting} onClick={sendMoreThanBalance}>
                Insufficient funds
              </Button>
              <Button variant="secondary" disabled={submitting || !lastRequest} onClick={sendDuplicate}>
                Duplicate request
              </Button>
              <Button variant="secondary" disabled={submitting || !lastRequest} onClick={sendKeyConflict}>
                Key conflict
              </Button>
              <Button variant="secondary" disabled={submitting} onClick={sendHighValue}>
                High value
              </Button>
              <Button variant="secondary" disabled={submitting} onClick={sendBurst} className="col-span-2">
                Rapid payments (7x)
              </Button>
            </div>
          </Card>
        </div>

        {/* Right column: result */}
        <div className="space-y-6 lg:col-span-2">
          {error !== null && <ErrorMessage error={error} />}

          {!payment && error === null && (
            <Card>
              <p className="py-8 text-center text-sm text-gray-500">No payment sent yet.</p>
            </Card>
          )}

          {payment && (
            <Card
              title="Result"
              actions={
                canRefund && (
                  <Button variant="secondary" disabled={submitting} onClick={refund}>
                    Refund
                  </Button>
                )
              }
            >
              <dl className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-3">
                <Detail label="Payment ID" value={<span className="font-mono text-xs">{payment.id}</span>} />
                <Detail label="Status" value={<StatusBadge value={payment.status} />} />
                <Detail label="Amount" value={formatINR(payment.amount)} />
                <Detail label="From" value={accountName(accountList, payment.sourceAccountId)} />
                <Detail label="To" value={accountName(accountList, payment.destinationAccountId)} />
                <Detail label="Response" value={result.replayed ? `${result.httpStatus} (duplicate)` : result.httpStatus} />
                <Detail label="Fraud decision" value={<StatusBadge value={payment.fraud?.decision} />} />
                <Detail label="Risk score" value={payment.fraud?.score ?? '-'} />
                <Detail label="Rules" value={payment.fraud?.triggeredRules.join(', ') || '-'} />
                {payment.failureReason && <Detail label="Reason" value={payment.failureReason} />}
                {payment.refundedAmount > 0 && <Detail label="Refunded" value={formatINR(payment.refundedAmount)} />}
              </dl>
            </Card>
          )}

          {payment && (
            <Card title="Ledger entries">
              <LedgerTable entries={ledger.flatMap((tx) => tx.entries)} accounts={accountList} />
            </Card>
          )}

          {payment && checks.length > 0 && (
            <Card title="Balance check">
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                {checks.map((check) => (
                  <div key={check.accountId} className="flex items-center justify-between rounded-md border border-gray-200 px-4 py-3 text-sm">
                    <span>{accountName(accountList, check.accountId)}</span>
                    <StatusBadge value={check.status} />
                  </div>
                ))}
              </div>
            </Card>
          )}
        </div>
      </div>
    </div>
  )
}

function Detail({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div>
      <dt className="text-gray-500">{label}</dt>
      <dd className="mt-1 text-gray-900">{value}</dd>
    </div>
  )
}
