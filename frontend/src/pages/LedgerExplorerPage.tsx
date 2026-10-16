import { useState } from 'react'
import { api } from '../services/api'
import { useAsync } from '../hooks/useAsync'
import type { BalanceVerification, EntryDirection, LedgerTransactionType, LedgerVerification } from '../types/api'
import { Button, Card, ErrorMessage, Field, Loading, PageHeader, Pagination, inputClass } from '../components/ui'
import { AccountSelect, AccountVerificationResult, LedgerEntriesTable, LedgerVerificationResult } from '../components/domain'

interface Filters {
  accountId: string
  paymentId: string
  direction: '' | EntryDirection
  transactionType: '' | LedgerTransactionType
  from: string
  to: string
}

const emptyFilters: Filters = { accountId: '', paymentId: '', direction: '', transactionType: '', from: '', to: '' }
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** datetime-local value (browser local time) → ISO instant for the API. */
const toIso = (local: string) => (local ? new Date(local).toISOString() : undefined)

export function LedgerExplorerPage() {
  const [draft, setDraft] = useState<Filters>(emptyFilters)
  const [filters, setFilters] = useState<Filters>(emptyFilters)
  const [page, setPage] = useState(0)

  const entries = useAsync(
    () =>
      api.ledgerEntries({
        accountId: filters.accountId || undefined,
        paymentId: filters.paymentId || undefined,
        direction: filters.direction || undefined,
        transactionType: filters.transactionType || undefined,
        from: toIso(filters.from),
        to: toIso(filters.to),
        page,
        size: 25,
      }),
    [filters, page],
  )

  const [verifying, setVerifying] = useState(false)
  const [verifyError, setVerifyError] = useState<unknown>()
  const [ledgerResult, setLedgerResult] = useState<LedgerVerification>()
  const [accountResult, setAccountResult] = useState<BalanceVerification>()

  async function verify() {
    setVerifying(true)
    setVerifyError(undefined)
    try {
      const [ledger, account] = await Promise.all([
        api.verifyLedger(),
        filters.accountId ? api.verifyAccount(filters.accountId) : Promise.resolve(undefined),
      ])
      setLedgerResult(ledger)
      setAccountResult(account)
    } catch (e) {
      setVerifyError(e)
    } finally {
      setVerifying(false)
    }
  }

  const paymentIdInvalid = draft.paymentId !== '' && !UUID_PATTERN.test(draft.paymentId.trim())
  const set = <K extends keyof Filters>(key: K, value: Filters[K]) => setDraft((d) => ({ ...d, [key]: value }))

  return (
    <>
      <PageHeader
        title="Ledger Explorer"
        description="Append-only double-entry ledger. Balance = credits − debits for every account."
        actions={
          <Button onClick={verify} disabled={verifying}>
            {verifying ? 'Verifying…' : 'Verify ledger'}
          </Button>
        }
      />
      <div className="space-y-6">
        {verifyError !== undefined && <ErrorMessage error={verifyError} />}
        {(ledgerResult || accountResult) && (
          <Card title="Verification results (computed by the backend)">
            <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
              <div className="lg:col-span-2">{ledgerResult && <LedgerVerificationResult result={ledgerResult} />}</div>
              {accountResult && <AccountVerificationResult result={accountResult} />}
            </div>
          </Card>
        )}

        <Card title="Filters">
          <form
            className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-6"
            onSubmit={(e) => {
              e.preventDefault()
              if (paymentIdInvalid) return
              setPage(0)
              setFilters({ ...draft, paymentId: draft.paymentId.trim() })
            }}
          >
            <Field label="Account">
              <AccountSelect value={draft.accountId} onChange={(v) => set('accountId', v)} allowEmpty="All accounts" />
            </Field>
            <Field label="Payment ID" hint={paymentIdInvalid ? 'Enter a full payment UUID' : undefined}>
              <input className={inputClass} value={draft.paymentId} placeholder="UUID" onChange={(e) => set('paymentId', e.target.value)} />
            </Field>
            <Field label="Direction">
              <select className={inputClass} value={draft.direction} onChange={(e) => set('direction', e.target.value as Filters['direction'])}>
                <option value="">Any</option>
                <option value="DEBIT">Debit</option>
                <option value="CREDIT">Credit</option>
              </select>
            </Field>
            <Field label="Type">
              <select className={inputClass} value={draft.transactionType} onChange={(e) => set('transactionType', e.target.value as Filters['transactionType'])}>
                <option value="">Any</option>
                <option value="OPENING_BALANCE">Opening balance</option>
                <option value="TRANSFER">Transfer</option>
                <option value="REFUND">Refund</option>
              </select>
            </Field>
            <Field label="From">
              <input className={inputClass} type="datetime-local" value={draft.from} onChange={(e) => set('from', e.target.value)} />
            </Field>
            <Field label="To">
              <input className={inputClass} type="datetime-local" value={draft.to} onChange={(e) => set('to', e.target.value)} />
            </Field>
            <div className="flex gap-2 sm:col-span-2 lg:col-span-6">
              <Button type="submit" disabled={paymentIdInvalid}>
                Apply
              </Button>
              <Button
                type="button"
                variant="ghost"
                onClick={() => {
                  setDraft(emptyFilters)
                  setFilters(emptyFilters)
                  setPage(0)
                }}
              >
                Clear
              </Button>
            </div>
          </form>
        </Card>

        <Card title={entries.data ? `Ledger entries (${entries.data.totalElements})` : 'Ledger entries'}>
          {entries.error && <ErrorMessage error={entries.error} onRetry={entries.reload} />}
          {entries.loading && !entries.data && <Loading />}
          {entries.data && (
            <>
              <LedgerEntriesTable entries={entries.data.content} />
              <Pagination page={entries.data.page} totalPages={entries.data.totalPages} onChange={setPage} />
            </>
          )}
        </Card>
      </div>
    </>
  )
}
