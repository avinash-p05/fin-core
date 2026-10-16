import { api } from '../services/api'
import { formatDateTime, formatInr } from '../services/format'
import { useAsync } from '../hooks/useAsync'
import { Button, Card, ErrorMessage, Loading, PageHeader } from '../components/ui'
import { PaymentsTable, StatCard } from '../components/domain'

export function OverviewPage() {
  const { data, error, loading, reload } = useAsync(() => api.dashboard(), [])

  return (
    <>
      <PageHeader
        title="Overview"
        description="All figures are aggregated by the backend from PostgreSQL."
        actions={
          <Button variant="secondary" onClick={reload} disabled={loading}>
            Refresh
          </Button>
        }
      />
      {error && <ErrorMessage error={error} onRetry={reload} />}
      {loading && !data && <Loading />}
      {data && (
        <div className="space-y-6">
          <div className="grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-6">
            <StatCard label="Total payments" value={data.totalPayments} />
            <StatCard label="Fraud approved" value={data.approvedPayments} tone="green" />
            <StatCard label="Under review" value={data.underReviewPayments} tone="amber" />
            <StatCard label="Declined" value={data.declinedPayments} tone="red" />
            <StatCard label="Failed" value={data.failedPayments} />
            <StatCard label="Settled value" value={formatInr(data.settledValue)} />
          </div>
          <div className="grid grid-cols-1 gap-4 text-sm text-slate-600 sm:grid-cols-3">
            <div className="rounded-md bg-white px-4 py-2 ring-1 ring-slate-200">
              Open fraud alerts: <span className="font-semibold text-slate-900">{data.openFraudAlerts}</span>
            </div>
            <div className="rounded-md bg-white px-4 py-2 ring-1 ring-slate-200">
              Refunded value: <span className="font-semibold text-slate-900">{formatInr(data.refundedValue)}</span>
            </div>
            <div className="rounded-md bg-white px-4 py-2 ring-1 ring-slate-200">
              Outbox: <span className="font-semibold text-slate-900">{data.pendingOutboxEvents}</span> pending,{' '}
              <span className={data.failedOutboxEvents > 0 ? 'font-semibold text-rose-700' : 'font-semibold text-slate-900'}>
                {data.failedOutboxEvents}
              </span>{' '}
              failed
            </div>
          </div>
          <Card title="Recent transactions" actions={<span className="text-xs text-slate-400">as of {formatDateTime(data.generatedAt)}</span>}>
            <PaymentsTable payments={data.recentPayments} />
          </Card>
        </div>
      )}
    </>
  )
}
