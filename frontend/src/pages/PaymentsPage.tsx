import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../services/api'
import { toLabel } from '../services/format'
import { useFetch } from '../hooks/useFetch'
import type { PaymentStatus } from '../types/api'
import { Button, Card, ErrorMessage, PageHeader, Pagination, Spinner, filterClass } from '../components/ui'
import { PaymentTable } from '../components/tables'

const STATUSES: PaymentStatus[] = ['COMPLETED', 'PENDING_REVIEW', 'DECLINED', 'FAILED', 'PARTIALLY_REFUNDED', 'REFUNDED']

export function PaymentsPage() {
  const [status, setStatus] = useState<PaymentStatus | ''>('')
  const [accountId, setAccountId] = useState('')
  const [page, setPage] = useState(0)
  const navigate = useNavigate()

  const accounts = useFetch(() => api.accounts())
  const payments = useFetch(
    () => api.payments({ status: status || undefined, accountId: accountId || undefined, page, size: 15 }),
    [status, accountId, page],
  )

  return (
    <div>
      <PageHeader
        title="Transactions"
        actions={
          <Button onClick={() => navigate('/payments/new')}>New payment</Button>
        }
      />

      <Card>
        <div className="mb-4 flex flex-wrap gap-3">
          <select
            className={filterClass}
            value={accountId}
            onChange={(e) => {
              setAccountId(e.target.value)
              setPage(0)
            }}
          >
            <option value="">All accounts</option>
            {accounts.data?.map((a) => (
              <option key={a.id} value={a.id}>
                {a.displayName}
              </option>
            ))}
          </select>
          <select
            className={filterClass}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as PaymentStatus | '')
              setPage(0)
            }}
          >
            <option value="">All statuses</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {toLabel(s)}
              </option>
            ))}
          </select>
        </div>

        {payments.error && <ErrorMessage error={payments.error} />}
        {payments.loading && !payments.data && <Spinner />}
        {payments.data && (
          <>
            <PaymentTable payments={payments.data.content} accounts={accounts.data ?? []} />
            <Pagination page={payments.data.page} totalPages={payments.data.totalPages} onChange={setPage} />
          </>
        )}
      </Card>
    </div>
  )
}
