import type { BalanceVerification, LedgerEntry, LedgerVerification, Payment } from '../types/api'
import { formatDateTime, formatInr, shortId } from '../services/format'
import { useSession } from '../services/session'
import { DecisionBadge, DirectionBadge, PaymentStatusBadge, VerificationBadge } from './badges'
import { Empty, Mono, Table, Td, inputClass } from './ui'

export function StatCard({ label, value, tone = 'slate' }: { label: string; value: string | number; tone?: 'slate' | 'green' | 'amber' | 'red' }) {
  const accent = { slate: 'text-slate-900', green: 'text-emerald-700', amber: 'text-amber-700', red: 'text-rose-700' }[tone]
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <div className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</div>
      <div className={`mt-2 text-2xl font-semibold ${accent}`}>{value}</div>
    </div>
  )
}

export function AccountSelect({ value, onChange, exclude, allowEmpty }: { value: string; onChange: (id: string) => void; exclude?: string; allowEmpty?: string }) {
  const { accounts } = useSession()
  return (
    <select className={inputClass} value={value} onChange={(e) => onChange(e.target.value)}>
      {allowEmpty !== undefined && <option value="">{allowEmpty}</option>}
      {accounts
        .filter((a) => a.id !== exclude)
        .map((a) => (
          <option key={a.id} value={a.id}>
            {a.displayName} · {formatInr(a.balance)}
            {a.status === 'FROZEN' ? ' · FROZEN' : ''}
          </option>
        ))}
    </select>
  )
}

export function PaymentsTable({ payments }: { payments: Payment[] }) {
  const { accountName } = useSession()
  if (payments.length === 0) return <Empty>No payments yet. Use the Payment Simulator to create one.</Empty>
  return (
    <Table head={['Payment', 'From', 'To', 'Amount', 'Status', 'Fraud', 'Created']}>
      {payments.map((p) => (
        <tr key={p.id}>
          <Td>
            <Mono title={p.id}>{shortId(p.id)}</Mono>
          </Td>
          <Td>{accountName(p.sourceAccountId)}</Td>
          <Td>{accountName(p.destinationAccountId)}</Td>
          <Td className="text-right font-medium tabular-nums">{formatInr(p.amount)}</Td>
          <Td>
            <PaymentStatusBadge status={p.status} />
            {p.failureReason && <span className="ml-2 text-xs text-slate-500">{p.failureReason}</span>}
          </Td>
          <Td>
            <DecisionBadge decision={p.fraud?.decision} />
            {p.fraud && <span className="ml-2 text-xs text-slate-500">score {p.fraud.score}</span>}
          </Td>
          <Td className="text-xs text-slate-500">{formatDateTime(p.createdAt)}</Td>
        </tr>
      ))}
    </Table>
  )
}

export function LedgerEntriesTable({ entries, showPayment = true }: { entries: LedgerEntry[]; showPayment?: boolean }) {
  const { accountName } = useSession()
  if (entries.length === 0) return <Empty>No ledger entries match.</Empty>
  return (
    <Table head={[...(showPayment ? ['Payment'] : []), 'Type', 'Account', 'Direction', 'Amount', 'Posted']}>
      {entries.map((e) => (
        <tr key={e.id}>
          {showPayment && (
            <Td>
              <Mono title={e.paymentId ?? undefined}>{e.paymentId ? shortId(e.paymentId) : '—'}</Mono>
            </Td>
          )}
          <Td className="text-xs">{e.transactionType}</Td>
          <Td>{accountName(e.accountId)}</Td>
          <Td>
            <DirectionBadge direction={e.direction} />
          </Td>
          <Td className="text-right font-medium tabular-nums">{formatInr(e.amount)}</Td>
          <Td className="text-xs text-slate-500">{formatDateTime(e.postedAt)}</Td>
        </tr>
      ))}
    </Table>
  )
}

export function AccountVerificationResult({ result }: { result: BalanceVerification }) {
  const { accountName } = useSession()
  return (
    <div className="rounded-md border border-slate-200 p-3 text-sm">
      <div className="mb-2 flex items-center justify-between gap-2">
        <span className="font-medium text-slate-800">{accountName(result.accountId)}</span>
        <VerificationBadge status={result.status} />
      </div>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-slate-600">
        <dt>Stored balance</dt>
        <dd className="text-right tabular-nums">{formatInr(result.storedBalance)}</dd>
        <dt>Ledger balance (credits − debits)</dt>
        <dd className="text-right tabular-nums">{formatInr(result.ledgerBalance)}</dd>
        <dt>Difference</dt>
        <dd className="text-right tabular-nums">{formatInr(result.difference)}</dd>
        <dt>Entries</dt>
        <dd className="text-right tabular-nums">{result.entryCount}</dd>
      </dl>
    </div>
  )
}

export function LedgerVerificationResult({ result }: { result: LedgerVerification }) {
  const { accountName } = useSession()
  return (
    <div className="space-y-3 text-sm">
      <div className="flex items-center gap-3">
        <VerificationBadge status={result.status} />
        <span className="text-xs text-slate-500">Verified by the backend at {formatDateTime(result.verifiedAt)}</span>
      </div>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-slate-600 sm:grid-cols-4">
        <dt>Transactions</dt>
        <dd className="tabular-nums">{result.transactionsChecked}</dd>
        <dt>Entries</dt>
        <dd className="tabular-nums">{result.entriesChecked}</dd>
        <dt>Total debits</dt>
        <dd className="tabular-nums">{formatInr(result.totalDebits)}</dd>
        <dt>Total credits</dt>
        <dd className="tabular-nums">{formatInr(result.totalCredits)}</dd>
        <dt>Accounts reconciled</dt>
        <dd className="tabular-nums">{result.accountsChecked}</dd>
        <dt>Unbalanced transactions</dt>
        <dd className="tabular-nums">{result.unbalancedTransactions.length}</dd>
        <dt>Balance mismatches</dt>
        <dd className="tabular-nums">{result.accountMismatches.length}</dd>
        <dt>Settled payments without ledger</dt>
        <dd className="tabular-nums">{result.completedPaymentsWithoutLedger.length}</dd>
      </dl>
      {result.accountMismatches.length > 0 && (
        <ul className="list-inside list-disc text-xs text-rose-700">
          {result.accountMismatches.map((m) => (
            <li key={m.accountId}>
              {accountName(m.accountId)}: stored {formatInr(m.storedBalance)}, ledger {formatInr(m.ledgerBalance)}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
