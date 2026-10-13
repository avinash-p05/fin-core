# FinCore progress

## Toolchain (checked at start)

| Tool | Version found | Notes |
|---|---|---|
| Java | 21.0.2 (`~/.jdks/openjdk-21.0.2`) | System default is 17; build uses `JAVA_HOME` pointing to JDK 21 |
| Maven | 3.10.0 via `./mvnw` | No global Maven install needed |
| Node.js / npm | 22.23.3 / 10.9.9 | |
| Docker / Compose | 29.7.2 / v5.3.1 | Required for Testcontainers |

## Phase 1: backend skeleton, customers, accounts — DONE

- Spring Boot 4.1.1, Java 21, Flyway migration `V1__customers_and_accounts.sql`.
- Customer create/list/get; account create/list/get/balance/freeze/unfreeze.
- `CHECK` constraint prevents negative balances on customer accounts at the database level.
- Local-dev HTTP Basic security (operator / analyst roles), ProblemDetail error handling, OpenAPI.
- Tests: `AccountApiTest` — 5 tests, 5 passed (real PostgreSQL via Testcontainers).

## Phase 2: payments, ledger, idempotency — DONE

- Flyway `V2__payments_ledger_idempotency.sql`: payments, idempotency_keys, ledger_transactions, ledger_entries.
- Database-enforced invariants: deferred constraint trigger (debits = credits, ≥ 2 entries per
  ledger transaction, checked at COMMIT), immutability triggers on ledger tables, refund-bounds CHECK.
- Transfers lock both accounts (`SELECT ... FOR UPDATE`) in deterministic UUID order.
- Opening balances are explicit OPENING_BALANCE ledger transactions against the system funding account.
- Durable idempotency: `INSERT ... ON CONFLICT DO NOTHING` + SHA-256 request fingerprint, same transaction as the payment.
- Ledger history/search, per-payment ledger, per-account reconciliation, whole-ledger verification (REPEATABLE READ snapshot).
- Bug found by the concurrency test and fixed: `SELECT ... FOR UPDATE` via a repository query returned a stale
  entity already in the persistence context, causing lost updates. `RowLocks` now refreshes under the lock.
- Tests: 24 run, 24 passed (AccountApiTest 5, PaymentIntegrationTest 9, PaymentConcurrencyTest 3,
  LedgerIntegrityTest 5, PaymentApiTest 2).

## Phase 3: refunds, fraud scoring, Redis velocity, review workflow — DONE

- Flyway `V3__refunds_and_fraud.sql`: refunds, fraud columns on payments, fraud_alerts.
- Refunds: full or partial, idempotent, payment row locked to serialise refunds, compensating REFUND
  ledger transaction (original TRANSFER untouched), bounded by original amount (app check + DB CHECK).
- Fraud rules (configurable in `fincore.fraud.*`): HIGH_VALUE, VELOCITY (Redis), NEW_BENEFICIARY.
  Additive score → APPROVED / REVIEW / DECLINED. Evaluated before any balance change.
- Redis velocity: atomic Lua `INCR` + `PEXPIRE` fixed window; 500 ms timeouts.
  Redis unavailable → `VELOCITY_CHECK_UNAVAILABLE`, decision floor REVIEW (configurable to DECLINED; APPROVED rejected at startup).
- Alerts for REVIEW and DECLINED; analyst-only PATCH; APPROVED executes the held payment (re-checks funds),
  REJECTED declines it, DECLINED alerts can only be CLOSED. Alert row lock prevents double execution.
- Tests: 52 run, 52 passed (new: FraudServiceTest 9, FraudWorkflowTest 9, VelocityTrackerTest 3,
  RedisOutageTest 1, RefundIntegrationTest 6).
