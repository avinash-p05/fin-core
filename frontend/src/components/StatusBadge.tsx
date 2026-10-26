import { toLabel } from '../services/format'

type Color = 'green' | 'yellow' | 'red' | 'gray' | 'blue' | 'purple'

const colors: Record<Color, string> = {
  green: 'bg-green-100 text-green-800',
  yellow: 'bg-yellow-100 text-yellow-800',
  red: 'bg-red-100 text-red-800',
  gray: 'bg-gray-100 text-gray-700',
  blue: 'bg-blue-100 text-blue-800',
  purple: 'bg-purple-100 text-purple-800',
}

// Which color each backend status value is shown in.
const statusColors: Record<string, Color> = {
  // payment status
  COMPLETED: 'green',
  PENDING_REVIEW: 'yellow',
  DECLINED: 'red',
  FAILED: 'gray',
  PARTIALLY_REFUNDED: 'purple',
  REFUNDED: 'purple',
  // fraud decision
  APPROVED: 'green',
  REVIEW: 'yellow',
  // alert status
  OPEN: 'yellow',
  IN_REVIEW: 'blue',
  REJECTED: 'red',
  CLOSED: 'gray',
  // ledger
  DEBIT: 'red',
  CREDIT: 'green',
  VALID: 'green',
  INVALID: 'red',
  // account
  ACTIVE: 'green',
  FROZEN: 'gray',
}

export function StatusBadge({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="text-gray-400">-</span>
  const color = statusColors[value] ?? 'gray'
  return <span className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${colors[color]}`}>{toLabel(value)}</span>
}
