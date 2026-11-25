import { Link } from 'react-router-dom'
import { api } from '../services/api'
import { formatINR } from '../services/format'
import { useFetch } from '../hooks/useFetch'
import { AiBadge, Button, Card, ErrorMessage, PageHeader, Spinner } from '../components/ui'
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
          <CopilotCallout held={summary.data.underReviewPayments} />

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

/** Points visitors at the AI fraud copilot, which otherwise only shows inside an alert. */
function CopilotCallout({ held }: { held: number }) {
  return (
    <section className="rounded-xl border border-indigo-200 bg-gradient-to-r from-indigo-50 to-white p-5 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="max-w-2xl">
          <div className="flex items-center gap-2">
            <AiBadge />
            <h2 className="font-medium text-gray-900">Fraud copilot</h2>
          </div>
          <p className="mt-1 text-sm text-gray-600">
            An LLM reads a held payment's history and drafts a case summary with a suggested action. Every payment and
            amount it cites is checked against the database, and a person still makes the decision.
          </p>
          <p className="mt-2 text-sm text-gray-700">
            {held > 0 ? (
              <>
                <strong>
                  {held} held payment{held === 1 ? '' : 's'}
                </strong>{' '}
                waiting. Open one on Fraud Alerts and click <strong>Summarise</strong>.
              </>
            ) : (
              <>
                Nothing is held right now. Send ₹1,00,000 or more to someone the account has paid before, and it will be
                held for review.
              </>
            )}
          </p>
        </div>
        <Link
          to={held > 0 ? '/fraud' : '/payments/new'}
          className="shrink-0 rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
        >
          {held > 0 ? 'Try the copilot →' : 'Create a payment →'}
        </Link>
      </div>
    </section>
  )
}
