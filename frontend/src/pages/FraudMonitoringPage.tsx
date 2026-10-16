import { useState } from 'react'
import { api } from '../services/api'
import { useAsync } from '../hooks/useAsync'
import { formatDateTime, formatInr, humanize, shortId } from '../services/format'
import { useSession } from '../services/session'
import type { FraudAlert, FraudAlertStatus } from '../types/api'
import { Button, Card, Empty, ErrorMessage, Field, Loading, Mono, PageHeader, Pagination, Table, Td, compactInputClass, inputClass } from '../components/ui'
import { AlertStatusBadge, DecisionBadge, PaymentStatusBadge, RuleList } from '../components/badges'

const transitionLabel: Record<FraudAlertStatus, string> = {
  OPEN: 'Reopen',
  IN_REVIEW: 'Start review',
  APPROVED: 'Approve & release payment',
  REJECTED: 'Reject payment',
  CLOSED: 'Close alert',
}

export function FraudMonitoringPage() {
  const { isAnalyst, accountName, refreshAccounts } = useSession()
  const [status, setStatus] = useState<'' | FraudAlertStatus>('')
  const [decision, setDecision] = useState<'' | 'REVIEW' | 'DECLINED'>('')
  const [page, setPage] = useState(0)
  const alerts = useAsync(
    () => api.fraudAlerts({ status: status || undefined, decision: decision || undefined, page, size: 20 }),
    [status, decision, page],
  )

  const [selected, setSelected] = useState<FraudAlert>()
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<unknown>()

  async function transition(target: FraudAlertStatus) {
    if (!selected) return
    setBusy(true)
    setActionError(undefined)
    try {
      const updated = await api.updateAlertStatus(selected.id, target, note || undefined)
      setSelected(updated)
      setNote('')
      alerts.reload()
      refreshAccounts()
    } catch (e) {
      setActionError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <PageHeader title="Fraud Monitoring" description="Alerts raised by synchronous fraud screening. Review actions call the backend workflow." />
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
        <div className="space-y-4 xl:col-span-2">
          <Card
            title="Alerts"
            actions={
              <>
                <select className={compactInputClass} value={decision} onChange={(e) => { setPage(0); setDecision(e.target.value as typeof decision) }}>
                  <option value="">All decisions</option>
                  <option value="REVIEW">Review</option>
                  <option value="DECLINED">Declined</option>
                </select>
                <select className={compactInputClass} value={status} onChange={(e) => { setPage(0); setStatus(e.target.value as typeof status) }}>
                  <option value="">All statuses</option>
                  {(['OPEN', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'CLOSED'] as const).map((s) => (
                    <option key={s} value={s}>
                      {humanize(s)}
                    </option>
                  ))}
                </select>
                <Button variant="secondary" onClick={alerts.reload}>
                  Refresh
                </Button>
              </>
            }
          >
            {alerts.error && <ErrorMessage error={alerts.error} onRetry={alerts.reload} />}
            {alerts.loading && !alerts.data && <Loading />}
            {alerts.data && alerts.data.content.length === 0 && <Empty>No fraud alerts match these filters.</Empty>}
            {alerts.data && alerts.data.content.length > 0 && (
              <>
                <Table head={['Payment', 'Amount', 'Risk score', 'Rules', 'Decision', 'Alert status', 'Created']}>
                  {alerts.data.content.map((a) => (
                    <tr
                      key={a.id}
                      onClick={() => { setSelected(a); setActionError(undefined) }}
                      className={`cursor-pointer hover:bg-slate-50 ${selected?.id === a.id ? 'bg-indigo-50' : ''}`}
                    >
                      <Td>
                        <Mono title={a.paymentId}>{shortId(a.paymentId)}</Mono>
                      </Td>
                      <Td className="text-right tabular-nums">{formatInr(a.payment.amount)}</Td>
                      <Td className="text-center font-semibold tabular-nums">{a.riskScore}</Td>
                      <Td>
                        <RuleList rules={a.triggeredRules} />
                      </Td>
                      <Td>
                        <DecisionBadge decision={a.decision} />
                      </Td>
                      <Td>
                        <AlertStatusBadge status={a.status} />
                      </Td>
                      <Td className="text-xs text-slate-500">{formatDateTime(a.createdAt)}</Td>
                    </tr>
                  ))}
                </Table>
                <Pagination page={alerts.data.page} totalPages={alerts.data.totalPages} onChange={setPage} />
              </>
            )}
          </Card>
        </div>

        <div>
          <Card title="Alert detail">
            {!selected ? (
              <p className="text-sm text-slate-500">Select an alert to review it.</p>
            ) : (
              <div className="space-y-4 text-sm">
                <dl className="grid grid-cols-2 gap-y-2 text-xs">
                  <dt className="text-slate-500">Alert</dt>
                  <dd><Mono>{shortId(selected.id)}</Mono></dd>
                  <dt className="text-slate-500">Payment</dt>
                  <dd><Mono title={selected.paymentId}>{shortId(selected.paymentId)}</Mono></dd>
                  <dt className="text-slate-500">From → To</dt>
                  <dd>{accountName(selected.payment.sourceAccountId)} → {accountName(selected.payment.destinationAccountId)}</dd>
                  <dt className="text-slate-500">Amount</dt>
                  <dd className="font-medium">{formatInr(selected.payment.amount)}</dd>
                  <dt className="text-slate-500">Payment status</dt>
                  <dd><PaymentStatusBadge status={selected.payment.status} /></dd>
                  <dt className="text-slate-500">Risk score</dt>
                  <dd>{selected.riskScore}</dd>
                  <dt className="text-slate-500">Decision</dt>
                  <dd><DecisionBadge decision={selected.decision} /></dd>
                  <dt className="text-slate-500">Alert status</dt>
                  <dd><AlertStatusBadge status={selected.status} /></dd>
                  <dt className="text-slate-500">Reviewed by</dt>
                  <dd>{selected.reviewedBy ?? '—'}</dd>
                </dl>
                <div>
                  <div className="mb-1 text-xs text-slate-500">Triggered rules</div>
                  <RuleList rules={selected.triggeredRules} />
                </div>
                {selected.reviewNote && <p className="rounded bg-slate-50 p-2 text-xs text-slate-600">“{selected.reviewNote}”</p>}

                {selected.allowedTransitions.length === 0 ? (
                  <p className="text-xs text-slate-500">This alert is in a final state.</p>
                ) : !isAnalyst ? (
                  <p className="text-xs text-amber-700">Review decisions require the ANALYST role. Sign in as an analyst to act on this alert.</p>
                ) : (
                  <div className="space-y-2">
                    <Field label="Review note (optional)">
                      <textarea className={inputClass} rows={2} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} />
                    </Field>
                    <div className="flex flex-wrap gap-2">
                      {selected.allowedTransitions.map((t) => (
                        <Button key={t} variant={t === 'REJECTED' ? 'danger' : t === 'APPROVED' ? 'primary' : 'secondary'} disabled={busy} onClick={() => transition(t)}>
                          {transitionLabel[t]}
                        </Button>
                      ))}
                    </div>
                    {selected.decision === 'REVIEW' && (
                      <p className="text-xs text-slate-500">
                        Approving executes the held transfer now; the backend re-checks funds and may still mark it FAILED.
                      </p>
                    )}
                  </div>
                )}
                {actionError !== undefined && <ErrorMessage error={actionError} />}
              </div>
            )}
          </Card>
        </div>
      </div>
    </>
  )
}
