// k6 load test for POST /api/v1/payments against a running FinCore stack.
//
//   k6 run -e SCENARIO=spread loadtest/payments.js   # payments spread over 100 accounts
//   k6 run -e SCENARIO=hot    loadtest/payments.js   # every payment debits ONE hot account
//
// Options: BASE_URL (default http://localhost:8080), VUS (default 50), DURATION (default 60s),
// ACCOUNTS (default 100). Credentials are the local-development operator user.
import http from 'k6/http'
import { check } from 'k6'
import { Counter } from 'k6/metrics'
import encoding from 'k6/encoding'
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js'

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080'
const SCENARIO = __ENV.SCENARIO || 'spread'
const ACCOUNTS = Number(__ENV.ACCOUNTS || 100)
const USER = __ENV.FINCORE_USER || 'operator'
const PASSWORD = __ENV.FINCORE_PASSWORD || 'operator-dev-password'

const HEADERS = {
  'Content-Type': 'application/json',
  Authorization: `Basic ${encoding.b64encode(`${USER}:${PASSWORD}`)}`,
}

const byStatus = {
  COMPLETED: new Counter('payments_completed'),
  FAILED: new Counter('payments_failed'),
  PENDING_REVIEW: new Counter('payments_pending_review'),
  DECLINED: new Counter('payments_declined'),
}

export const options = {
  setupTimeout: '300s',
  scenarios: {
    payments: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '60s',
    },
  },
  thresholds: {
    // Tagged sub-metrics isolate the payment calls from the setup requests.
    'http_req_duration{name:POST /payments}': ['p(95)<5000'],
    'http_reqs{name:POST /payments}': ['count>0'],
    'http_req_failed{name:POST /payments}': ['rate<0.01'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
}

/** Creates fresh customers and accounts (₹10,00,000 opening balance each) through the real API. */
export function setup() {
  const run = uuidv4().slice(0, 8)
  const ids = []
  for (let i = 0; i < ACCOUNTS; i++) {
    const customer = http.post(`${BASE_URL}/api/v1/customers`,
      JSON.stringify({ fullName: `Load ${run}-${i}`, email: `load-${run}-${i}@example.com` }), { headers: HEADERS })
    if (customer.status !== 201) throw new Error(`customer setup failed: ${customer.status} ${customer.body}`)
    const account = http.post(`${BASE_URL}/api/v1/accounts`,
      JSON.stringify({ customerId: customer.json('id'), displayName: `Load ${i}`, openingBalance: 1000000 }), { headers: HEADERS })
    if (account.status !== 201) throw new Error(`account setup failed: ${account.status} ${account.body}`)
    ids.push(account.json('id'))
  }
  return { ids }
}

export default function (data) {
  const ids = data.ids
  let source
  let destination
  if (SCENARIO === 'hot') {
    source = ids[0]
    destination = ids[1 + Math.floor(Math.random() * (ids.length - 1))]
  } else {
    source = ids[Math.floor(Math.random() * ids.length)]
    do {
      destination = ids[Math.floor(Math.random() * ids.length)]
    } while (destination === source)
  }
  const amount = (1 + Math.floor(Math.random() * 50000) / 100).toFixed(2) // ₹1.00 – ₹500.99

  const res = http.post(`${BASE_URL}/api/v1/payments`,
    JSON.stringify({ sourceAccountId: source, destinationAccountId: destination, amount: Number(amount), description: 'k6' }),
    { headers: { ...HEADERS, 'Idempotency-Key': `k6-${uuidv4()}` }, tags: { name: 'POST /payments' } })

  const ok = check(res, { 'status is 201': (r) => r.status === 201 })
  if (ok) {
    const counter = byStatus[res.json('status')]
    if (counter) counter.add(1)
  }
}

export function handleSummary(data) {
  return {
    [`loadtest/results/${SCENARIO}.json`]: JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  }
}

function textSummary(data) {
  const m = data.metrics
  const d = m['http_req_duration{name:POST /payments}'].values
  const payments = m['http_reqs{name:POST /payments}'].values.count
  const seconds = data.state.testRunDurationMs / 1000 - (data.setup_data ? 0 : 0)
  const count = (name) => (m[name] ? m[name].values.count : 0)
  return [
    `scenario=${SCENARIO} vus=${__ENV.VUS || 50} duration=${__ENV.DURATION || "60s"}`,
    `payment_requests=${payments} (test run incl. setup ${seconds.toFixed(1)}s)`,
    `latency ms: avg=${d.avg.toFixed(1)} med=${d.med.toFixed(1)} p90=${d['p(90)'].toFixed(1)} p95=${d['p(95)'].toFixed(1)} p99=${d['p(99)'].toFixed(1)} max=${d.max.toFixed(1)}`,
    `payment_http_failed=${(m['http_req_failed{name:POST /payments}'].values.rate * 100).toFixed(2)}% checks=${(m.checks.values.rate * 100).toFixed(2)}%`,
    `completed=${count('payments_completed')} failed=${count('payments_failed')} review=${count('payments_pending_review')} declined=${count('payments_declined')}`,
    '',
  ].join('\n')
}
