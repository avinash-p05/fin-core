import type { ReactNode } from 'react'
import type { Account, LedgerEntry, Payment } from '../types/api'
import { formatDate, formatINR, shortId, toLabel } from '../services/format'
import { StatusBadge } from './StatusBadge'
import { EmptyState } from './ui'

/** Basic table wrapper with consistent styling. */
export function Table({ headers, children }: { headers: string[]; children: ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="min-w-full text-left text-sm">
        <thead className="border-b border-gray-200 bg-gray-50 text-xs uppercase text-gray-500">
          <tr>
            {headers.map((header) => (
              <th key={header} className="whitespace-nowrap px-4 py-3 font-medium">
                {header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">{children}</tbody>
      </table>
    </div>
  )
}

export function Cell({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <td className={`whitespace-nowrap px-4 py-3 text-gray-700 ${className}`}>{children}</td>
}

/** Looks up an account's display name; falls back to the short id. */
export function accountName(accounts: Account[], id: string) {
  return accounts.find((a) => a.id === id)?.displayName ?? shortId(id)
}

export function PaymentTable({ payments, accounts }: { payments: Payment[]; accounts: Account[] }) {
  if (payments.length === 0) return <EmptyState message="No payments found." />
  return (
    <Table headers={['ID', 'From', 'To', 'Amount', 'Status', 'Fraud', 'Date']}>
      {payments.map((p) => (
        <tr key={p.id} className="hover:bg-gray-50">
          <Cell className="font-mono text-xs">{shortId(p.id)}</Cell>
          <Cell>{accountName(accounts, p.sourceAccountId)}</Cell>
          <Cell>{accountName(accounts, p.destinationAccountId)}</Cell>
          <Cell className="font-medium">{formatINR(p.amount)}</Cell>
          <Cell>
            <StatusBadge value={p.status} />
          </Cell>
          <Cell>
            <StatusBadge value={p.fraud?.decision} />
          </Cell>
          <Cell className="text-gray-500">{formatDate(p.createdAt)}</Cell>
        </tr>
      ))}
    </Table>
  )
}

export function LedgerTable({ entries, accounts }: { entries: LedgerEntry[]; accounts: Account[] }) {
  if (entries.length === 0) return <EmptyState message="No ledger entries found." />
  return (
    <Table headers={['Payment', 'Type', 'Account', 'Direction', 'Amount', 'Date']}>
      {entries.map((e) => (
        <tr key={e.id} className="hover:bg-gray-50">
          <Cell className="font-mono text-xs">{shortId(e.paymentId)}</Cell>
          <Cell>{toLabel(e.transactionType)}</Cell>
          <Cell>{accountName(accounts, e.accountId)}</Cell>
          <Cell>
            <StatusBadge value={e.direction} />
          </Cell>
          <Cell className="font-medium">{formatINR(e.amount)}</Cell>
          <Cell className="text-gray-500">{formatDate(e.postedAt)}</Cell>
        </tr>
      ))}
    </Table>
  )
}
