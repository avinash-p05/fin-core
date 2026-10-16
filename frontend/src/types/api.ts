// TypeScript mirrors of the backend DTOs. Monetary amounts arrive as JSON numbers produced from
// BigDecimal; the dashboard only formats them and never performs financial calculations.

export type UUID = string

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface Me {
  username: string
  roles: string[]
}

export type AccountStatus = 'ACTIVE' | 'FROZEN'

export interface Account {
  id: UUID
  customerId: UUID | null
  type: 'CUSTOMER' | 'SYSTEM'
  displayName: string
  currency: string
  balance: number
  status: AccountStatus
  createdAt: string
  updatedAt: string
}

export type PaymentStatus =
  | 'PENDING_REVIEW'
  | 'COMPLETED'
  | 'PARTIALLY_REFUNDED'
  | 'REFUNDED'
  | 'DECLINED'
  | 'FAILED'

export type FraudDecision = 'APPROVED' | 'REVIEW' | 'DECLINED'
export type FraudRule = 'HIGH_VALUE' | 'VELOCITY' | 'NEW_BENEFICIARY' | 'VELOCITY_CHECK_UNAVAILABLE'

export interface FraudSummary {
  score: number
  decision: FraudDecision
  triggeredRules: FraudRule[]
}

export interface Payment {
  id: UUID
  sourceAccountId: UUID
  destinationAccountId: UUID
  amount: number
  currency: string
  description: string | null
  status: PaymentStatus
  failureReason: string | null
  refundedAmount: number
  fraud: FraudSummary | null
  createdAt: string
  updatedAt: string
  completedAt: string | null
}

export interface CreatePaymentRequest {
  sourceAccountId: UUID
  destinationAccountId: UUID
  amount: number
  description?: string
}

export interface Refund {
  id: UUID
  paymentId: UUID
  amount: number
  reason: string | null
  createdAt: string
  paymentStatus: PaymentStatus
  paymentRefundedAmount: number
}

export type EntryDirection = 'DEBIT' | 'CREDIT'
export type LedgerTransactionType = 'OPENING_BALANCE' | 'TRANSFER' | 'REFUND'

export interface LedgerEntry {
  id: UUID
  ledgerTransactionId: UUID
  transactionType: LedgerTransactionType
  paymentId: UUID | null
  refundId: UUID | null
  accountId: UUID
  direction: EntryDirection
  amount: number
  currency: string
  postedAt: string
}

export interface LedgerTransaction {
  id: UUID
  type: LedgerTransactionType
  paymentId: UUID | null
  refundId: UUID | null
  description: string | null
  postedAt: string
  totalDebits: number
  totalCredits: number
  balanced: boolean
  entries: LedgerEntry[]
}

export type VerificationStatus = 'VALID' | 'INVALID'

export interface BalanceVerification {
  accountId: UUID
  storedBalance: number
  ledgerBalance: number
  difference: number
  totalDebits: number
  totalCredits: number
  entryCount: number
  status: VerificationStatus
  verifiedAt: string
}

export interface LedgerVerification {
  status: VerificationStatus
  transactionsChecked: number
  entriesChecked: number
  accountsChecked: number
  totalDebits: number
  totalCredits: number
  unbalancedTransactions: { ledgerTransactionId: UUID; debits: number; credits: number; entryCount: number }[]
  accountMismatches: { accountId: UUID; storedBalance: number; ledgerBalance: number }[]
  completedPaymentsWithoutLedger: UUID[]
  verifiedAt: string
}

export type FraudAlertStatus = 'OPEN' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED' | 'CLOSED'

export interface FraudAlert {
  id: UUID
  paymentId: UUID
  riskScore: number
  decision: Exclude<FraudDecision, 'APPROVED'>
  triggeredRules: FraudRule[]
  status: FraudAlertStatus
  allowedTransitions: FraudAlertStatus[]
  reviewedBy: string | null
  reviewNote: string | null
  createdAt: string
  updatedAt: string
  payment: {
    id: UUID
    sourceAccountId: UUID
    destinationAccountId: UUID
    amount: number
    status: PaymentStatus
    failureReason: string | null
  }
}

export interface DashboardSummary {
  totalPayments: number
  approvedPayments: number
  underReviewPayments: number
  declinedPayments: number
  failedPayments: number
  settledPayments: number
  settledValue: number
  refundedValue: number
  openFraudAlerts: number
  pendingOutboxEvents: number
  failedOutboxEvents: number
  recentPayments: Payment[]
  generatedAt: string
}

/** RFC 9457 problem detail returned by the backend for every error. */
export interface ProblemDetail {
  type?: string
  title?: string
  status: number
  detail?: string
  code?: string
  fieldErrors?: Record<string, string>
}
