import type { EntryDirection, FraudAlertStatus, FraudDecision, FraudRule, PaymentStatus, VerificationStatus } from '../types/api'
import { humanize } from '../services/format'

type Tone = 'green' | 'amber' | 'red' | 'slate' | 'blue' | 'violet'

const tones: Record<Tone, string> = {
  green: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  amber: 'bg-amber-50 text-amber-800 ring-amber-600/20',
  red: 'bg-rose-50 text-rose-700 ring-rose-600/20',
  slate: 'bg-slate-100 text-slate-700 ring-slate-500/20',
  blue: 'bg-sky-50 text-sky-700 ring-sky-600/20',
  violet: 'bg-violet-50 text-violet-700 ring-violet-600/20',
}

export function Badge({ tone, children }: { tone: Tone; children: string }) {
  return <span className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${tones[tone]}`}>{children}</span>
}

const decisionTone: Record<FraudDecision, Tone> = { APPROVED: 'green', REVIEW: 'amber', DECLINED: 'red' }

export function DecisionBadge({ decision }: { decision: FraudDecision | null | undefined }) {
  return decision ? <Badge tone={decisionTone[decision]}>{decision}</Badge> : <Badge tone="slate">N/A</Badge>
}

const paymentTone: Record<PaymentStatus, Tone> = {
  COMPLETED: 'green',
  PARTIALLY_REFUNDED: 'violet',
  REFUNDED: 'violet',
  PENDING_REVIEW: 'amber',
  DECLINED: 'red',
  FAILED: 'slate',
}

export function PaymentStatusBadge({ status }: { status: PaymentStatus }) {
  return <Badge tone={paymentTone[status]}>{humanize(status)}</Badge>
}

const alertTone: Record<FraudAlertStatus, Tone> = {
  OPEN: 'amber',
  IN_REVIEW: 'blue',
  APPROVED: 'green',
  REJECTED: 'red',
  CLOSED: 'slate',
}

export function AlertStatusBadge({ status }: { status: FraudAlertStatus }) {
  return <Badge tone={alertTone[status]}>{humanize(status)}</Badge>
}

export function VerificationBadge({ status }: { status: VerificationStatus }) {
  return <Badge tone={status === 'VALID' ? 'green' : 'red'}>{status === 'VALID' ? 'VERIFIED' : 'MISMATCH'}</Badge>
}

export function DirectionBadge({ direction }: { direction: EntryDirection }) {
  return <Badge tone={direction === 'DEBIT' ? 'red' : 'green'}>{direction}</Badge>
}

export function RuleList({ rules }: { rules: FraudRule[] }) {
  if (rules.length === 0) return <span className="text-xs text-slate-400">None</span>
  return (
    <div className="flex flex-wrap gap-1">
      {rules.map((r) => (
        <Badge key={r} tone="slate">
          {humanize(r)}
        </Badge>
      ))}
    </div>
  )
}
