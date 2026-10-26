import { api } from '../services/api'
import { formatINR } from '../services/format'
import { useFetch } from '../hooks/useFetch'
import { Button, Card, ErrorMessage, PageHeader, Spinner } from '../components/ui'
import { StatCard } from '../components/StatCard'
import { PaymentTable } from '../components/tables'

export function DashboardPage() {
  const summary = useFetch(() => api.dashboard())
  const accounts = useFetch(() => api.accounts())

  return (
    <div>
      <PageHeader
        title="Dashboard"
        actions={
          <Button variant="secondary" onClick={summary.reload}>
            Refresh
          </Button>
        }
      />

      {summary.error && <ErrorMessage error={summary.error} />}
      {summary.loading && !summary.data && <Spinner />}

      {summary.data && (
        <div className="space-y-6">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard label="Total payments" value={summary.data.totalPayments} />
            <StatCard label="Completed volume" value={formatINR(summary.data.settledValue)} />
            <StatCard label="Under review" value={summary.data.underReviewPayments} />
            <StatCard label="Declined / failed" value={summary.data.declinedPayments + summary.data.failedPayments} />
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <StatCard label="Fraud approved" value={summary.data.approvedPayments} />
            <StatCard label="Open fraud alerts" value={summary.data.openFraudAlerts} />
            <StatCard label="Refunded" value={formatINR(summary.data.refundedValue)} />
          </div>

          <Card title="Recent transactions">
            <PaymentTable payments={summary.data.recentPayments} accounts={accounts.data ?? []} />
          </Card>
        </div>
      )}
    </div>
  )
}
