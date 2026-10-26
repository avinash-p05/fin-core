import { useState } from 'react'
import { api } from '../services/api'
import { formatINR } from '../services/format'
import { useFetch } from '../hooks/useFetch'
import type { EntryDirection, LedgerTransactionType, LedgerVerification } from '../types/api'
import { Button, Card, ErrorMessage, PageHeader, Pagination, Spinner, filterClass } from '../components/ui'
import { StatusBadge } from '../components/StatusBadge'
import { LedgerTable } from '../components/tables'

export function LedgerPage() {
  const [accountId, setAccountId] = useState('')
  const [direction, setDirection] = useState<EntryDirection | ''>('')
  const [type, setType] = useState<LedgerTransactionType | ''>('')
  const [page, setPage] = useState(0)

  const accounts = useFetch(() => api.accounts())
  const entries = useFetch(
    () =>
      api.ledgerEntries({
        accountId: accountId || undefined,
        direction: direction || undefined,
        transactionType: type || undefined,
        page,
        size: 20,
      }),
    [accountId, direction, type, page],
  )

  const [verification, setVerification] = useState<LedgerVerification | null>(null)
  const [verifying, setVerifying] = useState(false)
  const [verifyError, setVerifyError] = useState<unknown>(null)

  async function verifyLedger() {
    setVerifying(true)
    setVerifyError(null)
    try {
      setVerification(await api.verifyLedger())
    } catch (err) {
      setVerifyError(err)
    } finally {
      setVerifying(false)
    }
  }

  // Any filter change goes back to the first page.
  function changeFilter(update: () => void) {
    update()
    setPage(0)
  }

  return (
    <div>
      <PageHeader
        title="Ledger"
        actions={
          <Button onClick={verifyLedger} disabled={verifying}>
            {verifying ? 'Verifying...' : 'Verify ledger'}
          </Button>
        }
      />

      <div className="space-y-6">
        {verifyError !== null && <ErrorMessage error={verifyError} />}

        {verification && (
          <Card title="Verification result" actions={<StatusBadge value={verification.status} />}>
            <dl className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-4">
              <Stat label="Transactions" value={verification.transactionsChecked} />
              <Stat label="Entries" value={verification.entriesChecked} />
              <Stat label="Total debits" value={formatINR(verification.totalDebits)} />
              <Stat label="Total credits" value={formatINR(verification.totalCredits)} />
              <Stat label="Unbalanced transactions" value={verification.unbalancedTransactions.length} />
              <Stat label="Balance mismatches" value={verification.accountMismatches.length} />
              <Stat label="Accounts checked" value={verification.accountsChecked} />
            </dl>
          </Card>
        )}

        <Card>
          <div className="mb-4 flex flex-wrap gap-3">
            <select className={filterClass} value={accountId} onChange={(e) => changeFilter(() => setAccountId(e.target.value))}>
              <option value="">All accounts</option>
              {accounts.data?.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.displayName}
                </option>
              ))}
            </select>
            <select
              className={filterClass}
              value={direction}
              onChange={(e) => changeFilter(() => setDirection(e.target.value as EntryDirection | ''))}
            >
              <option value="">Debit and credit</option>
              <option value="DEBIT">Debit</option>
              <option value="CREDIT">Credit</option>
            </select>
            <select
              className={filterClass}
              value={type}
              onChange={(e) => changeFilter(() => setType(e.target.value as LedgerTransactionType | ''))}
            >
              <option value="">All types</option>
              <option value="OPENING_BALANCE">Opening balance</option>
              <option value="TRANSFER">Transfer</option>
              <option value="REFUND">Refund</option>
            </select>
          </div>

          {entries.error && <ErrorMessage error={entries.error} />}
          {entries.loading && !entries.data && <Spinner />}
          {entries.data && (
            <>
              <LedgerTable entries={entries.data.content} accounts={accounts.data ?? []} />
              <Pagination page={entries.data.page} totalPages={entries.data.totalPages} onChange={setPage} />
            </>
          )}
        </Card>
      </div>
    </div>
  )
}

function Stat({ label, value }: { label: string; value: string | number }) {
  return (
    <div>
      <dt className="text-gray-500">{label}</dt>
      <dd className="mt-1 font-medium text-gray-900">{value}</dd>
    </div>
  )
}
