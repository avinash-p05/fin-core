import type {
  Account,
  BalanceVerification,
  CreatePaymentRequest,
  DashboardSummary,
  EntryDirection,
  FraudAlert,
  FraudAlertStatus,
  FraudDecision,
  LedgerEntry,
  LedgerTransaction,
  LedgerTransactionType,
  LedgerVerification,
  Me,
  Page,
  Payment,
  PaymentStatus,
  ProblemDetail,
  Refund,
} from '../types/api'

const BASE = '/api/v1'
const CREDENTIALS_KEY = 'fincore.basic'

/** Error carrying the backend's problem detail (status, code, message). */
export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly problem?: ProblemDetail

  constructor(status: number, problem?: ProblemDetail) {
    super(problem?.detail ?? problem?.title ?? `Request failed with status ${status}`)
    this.status = status
    this.code = problem?.code
    this.problem = problem
  }
}

// --- Local-development credentials --------------------------------------------------------
// HTTP Basic credentials are kept in sessionStorage for the tab's lifetime. This mirrors the
// backend's dev-only authentication and is not suitable for production.

export function setCredentials(username: string, password: string) {
  sessionStorage.setItem(CREDENTIALS_KEY, btoa(`${username}:${password}`))
}

export function clearCredentials() {
  sessionStorage.removeItem(CREDENTIALS_KEY)
}

export function hasCredentials() {
  return sessionStorage.getItem(CREDENTIALS_KEY) !== null
}

type Query = Record<string, string | number | undefined | null>

export interface ApiResponse<T> {
  data: T
  status: number
  headers: Headers
}

async function request<T>(method: string, path: string, options: { query?: Query; body?: unknown; headers?: Record<string, string> } = {}): Promise<ApiResponse<T>> {
  const url = new URL(BASE + path, window.location.origin)
  for (const [key, value] of Object.entries(options.query ?? {})) {
    if (value !== undefined && value !== null && value !== '') url.searchParams.set(key, String(value))
  }
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }
  const credentials = sessionStorage.getItem(CREDENTIALS_KEY)
  if (credentials) headers.Authorization = `Basic ${credentials}`
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'

  const response = await fetch(url, {
    method,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  if (!response.ok) {
    let problem: ProblemDetail | undefined
    try {
      problem = (await response.json()) as ProblemDetail
    } catch {
      problem = undefined
    }
    if (response.status === 401) clearCredentials()
    throw new ApiError(response.status, problem)
  }
  const data = (response.status === 204 ? undefined : await response.json()) as T
  return { data, status: response.status, headers: response.headers }
}

const get = async <T,>(path: string, query?: Query) => (await request<T>('GET', path, { query })).data

/** Client-generated idempotency key; the simulator reuses it to demonstrate duplicate requests. */
export function newIdempotencyKey() {
  return `ui-${crypto.randomUUID()}`
}

export interface PaymentSubmission {
  payment: Payment
  replayed: boolean
  httpStatus: number
}

export const api = {
  me: () => get<Me>('/me'),

  dashboard: () => get<DashboardSummary>('/dashboard/summary'),

  accounts: () => get<Account[]>('/accounts'),
  accountBalance: (id: string) => get<{ accountId: string; balance: number; status: string }>(`/accounts/${id}/balance`),
  verifyAccount: (id: string) => get<BalanceVerification>(`/accounts/${id}/balance/verification`),

  payments: (query: { accountId?: string; status?: PaymentStatus; page?: number; size?: number }) =>
    get<Page<Payment>>('/payments', query),
  payment: (id: string) => get<Payment>(`/payments/${id}`),
  paymentLedger: (id: string) => get<LedgerTransaction[]>(`/payments/${id}/ledger`),

  async createPayment(idempotencyKey: string, body: CreatePaymentRequest): Promise<PaymentSubmission> {
    const res = await request<Payment>('POST', '/payments', { body, headers: { 'Idempotency-Key': idempotencyKey } })
    return { payment: res.data, replayed: res.headers.get('Idempotent-Replayed') === 'true', httpStatus: res.status }
  },

  async refund(paymentId: string, idempotencyKey: string, body: { amount?: number; reason?: string }) {
    const res = await request<Refund>('POST', `/payments/${paymentId}/refund`, {
      body,
      headers: { 'Idempotency-Key': idempotencyKey },
    })
    return { refund: res.data, replayed: res.headers.get('Idempotent-Replayed') === 'true' }
  },

  ledgerEntries: (query: {
    accountId?: string
    paymentId?: string
    direction?: EntryDirection
    transactionType?: LedgerTransactionType
    from?: string
    to?: string
    page?: number
    size?: number
  }) => get<Page<LedgerEntry>>('/ledger/entries', query),
  verifyLedger: () => get<LedgerVerification>('/ledger/verification'),

  fraudAlerts: (query: { status?: FraudAlertStatus; decision?: Exclude<FraudDecision, 'APPROVED'>; paymentId?: string; page?: number; size?: number }) =>
    get<Page<FraudAlert>>('/fraud/alerts', query),
  fraudAlert: (id: string) => get<FraudAlert>(`/fraud/alerts/${id}`),
  updateAlertStatus: async (id: string, status: FraudAlertStatus, note?: string) =>
    (await request<FraudAlert>('PATCH', `/fraud/alerts/${id}/status`, { body: { status, note } })).data,
}
