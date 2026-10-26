import { useState, type ReactNode } from 'react'
import { api } from '../services/api'
import { formatDate, formatINR, shortId } from '../services/format'
import { useFetch } from '../hooks/useFetch'
import { useAuth } from '../context/AuthContext'
import type { FraudAlert, FraudAlertStatus } from '../types/api'
import { Button, Card, ErrorMessage, Label, PageHeader, Pagination, Spinner, EmptyState, filterClass, inputClass } from '../components/ui'
import { StatusBadge } from '../components/StatusBadge'
import { Cell, Table, accountName } from '../components/tables'

// Button text for each status an alert can be moved to.
const actionLabels: Record<FraudAlertStatus, string> = {
  OPEN: 'Reopen',
  IN_REVIEW: 'Start review',
  APPROVED: 'Approve',
  REJECTED: 'Reject',
  CLOSED: 'Close',
}

export function FraudAlertsPage() {
  const { isAnalyst } = useAuth()
  const [status, setStatus] = useState<FraudAlertStatus | ''>('')
  const [page, setPage] = useState(0)

  const accounts = useFetch(() => api.accounts())
  const alerts = useFetch(() => api.fraudAlerts({ status: status || undefined, page, size: 15 }), [status, page])

  const [selected, setSelected] = useState<FraudAlert | null>(null)
  const [note, setNote] = useState('')
  const [saving, setSaving] = useState(false)
  const [actionError, setActionError] = useState<unknown>(null)

  async function changeStatus(newStatus: FraudAlertStatus) {
    if (!selected) return
    setSaving(true)
    setActionError(null)
    try {
      const updated = await api.updateAlertStatus(selected.id, newStatus, note || undefined)
      setSelected(updated)
      setNote('')
      alerts.reload()
    } catch (err) {
      setActionError(err)
    } finally {
      setSaving(false)
    }
  }

  const accountList = accounts.data ?? []

  return (
    <div>
      <PageHeader title="Fraud Alerts" />

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
        {/* Alert list */}
        <div className="xl:col-span-2">
          <Card>
            <div className="mb-4">
              <select
                className={filterClass}
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value as FraudAlertStatus | '')
                  setPage(0)
                }}
              >
                <option value="">All statuses</option>
                <option value="OPEN">Open</option>
                <option value="IN_REVIEW">In review</option>
                <option value="APPROVED">Approved</option>
                <option value="REJECTED">Rejected</option>
                <option value="CLOSED">Closed</option>
              </select>
            </div>

            {alerts.error && <ErrorMessage error={alerts.error} />}
            {alerts.loading && !alerts.data && <Spinner />}
            {alerts.data && alerts.data.content.length === 0 && <EmptyState message="No fraud alerts." />}
            {alerts.data && alerts.data.content.length > 0 && (
              <>
                <Table headers={['Payment', 'Amount', 'Score', 'Rules', 'Decision', 'Status', 'Date']}>
                  {alerts.data.content.map((alert) => (
                    <tr
                      key={alert.id}
                      onClick={() => {
                        setSelected(alert)
                        setActionError(null)
                      }}
                      className={`cursor-pointer hover:bg-gray-50 ${selected?.id === alert.id ? 'bg-blue-50' : ''}`}
                    >
                      <Cell className="font-mono text-xs">{shortId(alert.paymentId)}</Cell>
                      <Cell>{formatINR(alert.payment.amount)}</Cell>
                      <Cell className="font-medium">{alert.riskScore}</Cell>
                      <Cell className="text-xs">{alert.triggeredRules.join(', ')}</Cell>
                      <Cell>
                        <StatusBadge value={alert.decision} />
                      </Cell>
                      <Cell>
                        <StatusBadge value={alert.status} />
                      </Cell>
                      <Cell className="text-gray-500">{formatDate(alert.createdAt)}</Cell>
                    </tr>
                  ))}
                </Table>
                <Pagination page={alerts.data.page} totalPages={alerts.data.totalPages} onChange={setPage} />
              </>
            )}
          </Card>
        </div>

        {/* Selected alert */}
        <div>
          <Card title="Alert details">
            {!selected ? (
              <p className="text-sm text-gray-500">Select an alert from the list.</p>
            ) : (
              <div className="space-y-4 text-sm">
                <dl className="space-y-2">
                  <Row label="Payment" value={<span className="font-mono text-xs">{shortId(selected.paymentId)}</span>} />
                  <Row label="From" value={accountName(accountList, selected.payment.sourceAccountId)} />
                  <Row label="To" value={accountName(accountList, selected.payment.destinationAccountId)} />
                  <Row label="Amount" value={formatINR(selected.payment.amount)} />
                  <Row label="Payment status" value={<StatusBadge value={selected.payment.status} />} />
                  <Row label="Risk score" value={selected.riskScore} />
                  <Row label="Rules" value={selected.triggeredRules.join(', ')} />
                  <Row label="Decision" value={<StatusBadge value={selected.decision} />} />
                  <Row label="Alert status" value={<StatusBadge value={selected.status} />} />
                  <Row label="Reviewed by" value={selected.reviewedBy ?? '-'} />
                  {selected.reviewNote && <Row label="Note" value={selected.reviewNote} />}
                </dl>

                {selected.allowedTransitions.length > 0 && isAnalyst && (
                  <div className="space-y-3 border-t border-gray-200 pt-4">
                    <Label text="Note">
                      <textarea className={inputClass} rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
                    </Label>
                    <div className="flex flex-wrap gap-2">
                      {selected.allowedTransitions.map((next) => (
                        <Button
                          key={next}
                          variant={next === 'REJECTED' ? 'danger' : next === 'APPROVED' ? 'primary' : 'secondary'}
                          disabled={saving}
                          onClick={() => changeStatus(next)}
                        >
                          {actionLabels[next]}
                        </Button>
                      ))}
                    </div>
                  </div>
                )}

                {selected.allowedTransitions.length > 0 && !isAnalyst && (
                  <p className="border-t border-gray-200 pt-4 text-gray-500">Only analysts can review alerts.</p>
                )}

                {actionError !== null && <ErrorMessage error={actionError} />}
              </div>
            )}
          </Card>
        </div>
      </div>
    </div>
  )
}

function Row({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex justify-between gap-4">
      <dt className="text-gray-500">{label}</dt>
      <dd className="text-right text-gray-900">{value}</dd>
    </div>
  )
}
