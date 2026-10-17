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

## Phase 4: transactional outbox and Kafka — DONE

- Flyway `V4__outbox_and_consumer.sql`: outbox_events (JSONB payload), processed_events, payment_notifications.
- `PaymentCompleted` / `PaymentRefunded` events written to the outbox in the same transaction as
  the ledger posting (`Propagation.MANDATORY`).
- `OutboxPublisher`: `FOR UPDATE SKIP LOCKED` batch claim, synchronous send with timeout, exponential
  backoff, parked as FAILED after `max-attempts`; stops a batch at the first failure.
- Consumer (`notification` package, in-process, toggle `fincore.consumer.enabled`): de-duplicates on
  `(consumer, event_id)` in the same DB transaction as its effect; 3 bounded retries then `<topic>.DLT`;
  malformed JSON is not retried.
- Tests: 61 run, 61 passed (new: OutboxPublisherTest 4, OutboxKafkaIntegrationTest 5 against real Kafka).

## Phase 5: React operations dashboard — DONE

- `frontend/`: React 19, Vite 8, TypeScript 5.9, Tailwind CSS 4, React Router 7. No chart library (not needed).
- Pages: Overview (backend `/dashboard/summary`), Payment Simulator, Ledger Explorer, Fraud Monitoring.
- Centralised API client (`services/api.ts`) with typed responses (`types/api.ts`) and problem-detail errors;
  loading / error / empty states; INR formatting via `Intl.NumberFormat('en-IN')`.
- Simulator demos all hit the real API: insufficient funds, duplicate request (same key), key reuse (409),
  high-value (REVIEW / DECLINED depending on beneficiary history), velocity burst, refund.
- Backend additions: `GET /api/v1/dashboard/summary`, `GET /api/v1/me`, optional demo seed (`FINCORE_DEMO_SEED=true`).
- Verified manually against a locally running backend + Docker infra: login, overview, simulator outcomes
  (201 / 200 replay / 409 / FAILED insufficient funds / PENDING_REVIEW), analyst approval (one released
  payment COMPLETED, one FAILED on re-check), ledger verification VALID.
- `npm run build` passes (tsc + vite). Backend tests: 61 run, 61 passed.

## Phase 6: Docker, documentation, final verification — DONE

- `infrastructure/docker-compose.yml`: PostgreSQL 16, Redis 7, Kafka 3.8 (KRaft), backend, frontend (nginx).
  Multi-stage Dockerfiles for backend (Temurin 21, non-root) and frontend (Node 22 build → nginx).
- Verified: `docker compose up -d --build` → all services healthy; dashboard served on :3000 with the
  `/api` proxy; dashboard summary and ledger verification (VALID) through nginx.
- Manual Redis outage check on the Compose stack: with Redis stopped a ₹100 payment returned
  `PENDING_REVIEW` with `VELOCITY_CHECK_UNAVAILABLE` (~0.8 s); after restarting Redis payments were approved again.
- Added `FraudPropertiesValidationTest` (startup fails for APPROVED-on-outage and inverted thresholds).
- Fixed `/api/v1/me` to return only roles (Spring Security 7 also grants `FACTOR_PASSWORD`).
- Docs: README.md, docs/architecture.md, docs/interview-guide.md.
- Final results: `./mvnw test` → **64 tests, 64 passed, 0 failed, 0 skipped**; `npm run build` → success.

## Known issues / next steps

- See "Known limitations" in README.md (dev-only auth, fixed-window velocity, no fund reservation for
  held payments, possible event reordering under outbox backoff, no admin replay for FAILED outbox rows).
- Possible next steps: admin endpoint to replay FAILED outbox events; authorisation holds for REVIEW
  payments; sliding-window velocity; OIDC authentication; frontend component tests.
