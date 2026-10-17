# FinCore interview guide

This guide explains how FinCore handles each topic and where the code is. Everything below
describes what is actually implemented. Limitations are stated where they apply.

---

## 1. Spring transaction boundaries

- `PaymentService.create`, `RefundService.refund`, `FraudReviewService.updateStatus` and
  `AccountService.create` are each a single `@Transactional` boundary. A request either commits
  everything (idempotency key, payment, fraud alert, balances, ledger, outbox row) or nothing.
- Methods that must never run alone use `Propagation.MANDATORY`: `LedgerService.post`,
  `OutboxService.record`, `IdempotencyService.claim`, `PaymentService.settle`,
  `AccountService.lockInOrder`. If one of them is called without an active transaction, Spring
  throws, which catches wiring mistakes early. It also makes the outbox guarantee concrete: an
  event can't be recorded outside the business transaction.
- **Proxy caveat.** `@Transactional` only applies through the Spring proxy. A call from one method
  of a bean to another method of the same bean (self-invocation) bypasses the proxy. FinCore is
  safe here because the inner methods are already inside the outer transaction.
- **Rollback rules.** Unchecked exceptions roll back. Business outcomes such as insufficient funds
  are *not* exceptions. They are persisted as `FAILED`, so they commit and replay. Validation
  errors are exceptions, so they roll back, including the idempotency-key claim, and the client
  can retry with the same key. `LedgerIntegrityTest.failureAfterTransferRollsBack…` shows that an
  outer transaction failure undoes everything.
- **Read-only checks.** Verification uses `readOnly = true, isolation = REPEATABLE_READ`, so all
  of its SQL statements see one consistent snapshot.
- `spring.jpa.open-in-view=false`: no lazy loading in controllers after the transaction has ended.

## 2. ACID in this project

- **Atomicity.** One PostgreSQL transaction per payment. The deferred trigger rejects an
  unbalanced ledger at COMMIT, which aborts the whole transaction.
- **Consistency.** CHECK constraints (no overdraft, refund bounds, positive amounts), foreign
  keys, unique keys, and the double-entry and immutability triggers. These hold even if
  application code has a bug.
- **Isolation.** PostgreSQL's default READ COMMITTED plus explicit row locks for writes, and
  REPEATABLE READ snapshots for verification reads.
- **Durability.** A commit is durable in PostgreSQL (WAL). Kafka publication happens only after
  commit, through the outbox, so a crash can't produce an event for an uncommitted payment.

## 3. Idempotency

- The client sends an `Idempotency-Key` header. The server computes a SHA-256 fingerprint over a
  canonical form of the request (scope, accounts, amount normalised to 2 decimals, currency,
  description). `100` and `100.00` therefore produce the same fingerprint.
- `INSERT INTO idempotency_keys … ON CONFLICT DO NOTHING` runs **in the same transaction** as the
  payment:
  - Inserted → first time; execute the request and attach the payment id.
  - Conflict with the same fingerprint → replay. The server returns the stored payment with HTTP
    200 and `Idempotent-Replayed: true`.
  - Conflict with a different fingerprint, or a different scope (payment vs refund) → **409**
    `IDEMPOTENCY_KEY_REUSED`.
- **Concurrent duplicates.** The second INSERT blocks on the unique index until the first
  transaction ends. If the first committed, the second sees the conflict and replays. If it rolled
  back, the second claims the key. `PaymentConcurrencyTest` sends 8 parallel requests with the same
  key: exactly one executes.
- Failed outcomes (insufficient funds) are stored and replayed. A later retry with the same key
  doesn't execute again, even if funds have arrived since.
- **Limitation.** A replay returns the payment's *current* state, not a stored copy of the
  original response body.

## 4. Race conditions and locking

- The classic bug is two withdrawals that each read a balance of 500 and each subtract 100 (lost
  update), or both pass the funds check (overdraft).
- FinCore locks both accounts with `SELECT … FOR UPDATE` before checking funds. The second
  transaction waits, then reads the committed balance.
- **Deadlock avoidance.** Locks are always taken in sorted UUID order, so A→B and B→A both lock the
  lower id first.
- **The JPA trap this project hit.** Spring Data's `@Lock(PESSIMISTIC_WRITE)` query *does* lock the
  row. But if the entity is already in the persistence context (it was loaded earlier in the same
  transaction for validation), Hibernate returns the cached instance and doesn't refresh its state.
  The code then subtracts from a stale balance and the flush overwrites the other transaction's
  update. `PaymentConcurrencyTest` failed with 10 of 10 withdrawals "succeeding" from a balance of
  500. The fix is `RowLocks.lockForUpdate`, which calls
  `entityManager.refresh(entity, PESSIMISTIC_WRITE)` to re-read the row under the lock.
- **Optimistic vs pessimistic.** `@Version` exists on payments and alerts as a safety net. Hot
  money paths use pessimistic locks because conflicts are expected and retrying a transfer is
  more complex than waiting for a lock.
- **Defence in depth.** Even if the locking were wrong, the `balance >= 0` CHECK would reject an
  overdraft at the database.

## 5. Double-entry bookkeeping

- Every movement is a `ledger_transaction` with at least two `ledger_entries`, and
  Σ debits = Σ credits.
- Convention: **balance = Σ credits − Σ debits** for every account. A transfer DEBITs the payer
  and CREDITs the payee.
- Opening balances are real ledger transactions against a system funding account. Money never
  appears without an offsetting entry, so Σ of all balances = 0.
- **Immutability.** Triggers reject UPDATE and DELETE, and the entities are Hibernate `@Immutable`.
- **Verification** (`LedgerQueryService`):
  - per account: stored balance vs Σ credits − Σ debits;
  - whole ledger: unbalanced transactions, mismatched accounts, settled payments without exactly
    one TRANSFER, and global debit and credit totals.
  - `LedgerIntegrityTest` corrupts a balance with direct SQL and checks that verification reports
    INVALID.

## 6. Refunds and compensating transactions

- A refund never edits the original transfer. It posts a new `REFUND` ledger transaction with the
  directions reversed (DEBIT payee, CREDIT payer) and links it through `refund_id`.
- Refunds can be partial and can repeat until `refunded_amount = amount`. The bound is checked
  under the payment row lock and enforced again by a CHECK constraint.
- Duplicate refunds: the same key returns a replay. Different keys are serialised by the payment
  lock, so the second one fails with `REFUND_EXCEEDS_REMAINING` or `PAYMENT_NOT_REFUNDABLE`.
  `RefundIntegrationTest` runs 6 concurrent full refunds and exactly one succeeds.
- The payee must still hold the funds. A refund never creates a negative customer balance.

## 7. Fraud scoring

- Rules are synchronous and additive (`FraudService`):
  - HIGH_VALUE: 50 points
  - VELOCITY: 40 points
  - NEW_BENEFICIARY: 20 points
- A score of 40 or more means REVIEW, and 70 or more means DECLINED. Everything is configurable
  under `fincore.fraud.*`, and the configuration is validated at startup (for example, the decline
  threshold must be greater than the review threshold).
- Screening runs **before** money moves, inside the payment transaction:
  - APPROVED → settle now.
  - REVIEW → `PENDING_REVIEW` plus an alert. No debit and no ledger.
  - DECLINED → `DECLINED` plus an alert. Balances never change.
- Review: an analyst (`ANALYST` role) approves, which executes the transfer with funds re-checked,
  or rejects, which declines the payment. Each alert stores the score, decision, triggered rules,
  status, reviewer and note.
- **Trade-off.** Rules are explainable and testable but crude: no ML, device or geo signals, and
  no per-customer limits.

## 8. Redis velocity detection

- Key: `fincore:velocity:{sourceAccountId}`. A Lua script runs `INCR` and, on the first increment,
  `PEXPIRE`. Redis runs the script atomically: no lost increments, and no key without a TTL.
  `VelocityTrackerTest` runs 50 parallel increments and observes counts 1..50 exactly once each.
- **Fixed window.** Simple and O(1). The weakness is that a burst across a window boundary can
  reach about twice the limit. A sliding-window log (sorted set) or token bucket would fix that at
  more cost.
- **Redis unavailable.** The 500 ms timeout surfaces as `VelocityUnavailableException`. The policy
  is **fail closed**: rule `VELOCITY_CHECK_UNAVAILABLE` is recorded and the decision is at least
  REVIEW (configurable to DECLINED; APPROVED is rejected at startup). The control is never
  silently bypassed, and payments keep flowing into the review queue instead of failing outright.
- The counter increments even if the database transaction later rolls back, so it slightly
  over-counts. Over-counting is the safe direction for a fraud control.

## 9. Transactional outbox

- **The problem.** "Save to the database, then publish to Kafka" has two failure windows: the
  publish fails after commit (event lost), or the publish succeeds and the commit fails (phantom
  event).
- **The fix.** Write the event to `outbox_events` in the same database transaction. A separate
  relay (`OutboxPublisher`) reads committed rows and publishes them.
- Relay details:
  - `FOR UPDATE SKIP LOCKED` allows horizontal scaling.
  - Synchronous send with a timeout.
  - Exponential backoff, and rows are parked as FAILED after 10 attempts.
  - A batch stops at the first failure.
- Alternative: change data capture (Debezium) reads the WAL instead of polling. It is better at
  scale but adds infrastructure.

## 10. Kafka delivery semantics

- Producer: `acks=all` and `enable.idempotence=true`. Idempotence prevents duplicates caused by
  the *producer's own* retries, but **not** the outbox re-sending a row after a crash. End to end
  this is **at-least-once**.
- Consumer: offsets are committed after processing (`ack-mode: record`). A crash between the
  database commit and the offset commit causes redelivery, so delivery is also at-least-once.
- Exactly-once is not claimed. Kafka transactions (EOS) only cover Kafka-to-Kafka. Writes to
  PostgreSQL still need an idempotent consumer.
- Ordering is per partition, keyed by payment id. Backoff retries can still reorder events of the
  same payment.
- Errors: 3 in-process retries, then the DLT. Malformed messages go straight to the DLT.
  `OutboxKafkaIntegrationTest` covers both cases.

## 11. Consumer idempotency

- `processed_events(consumer, event_id)` is the primary key. The handler inserts the marker with
  `ON CONFLICT DO NOTHING`. If nothing was inserted, the event is a duplicate and is skipped.
  Otherwise the handler writes its effect (notifications) **in the same transaction**.
- A failure rolls back both the marker and the effect, so retries are clean.
- A second guard, `UNIQUE (event_id, account_id)` on notifications, protects the effect itself.
- Tested by re-publishing an already-published outbox row and by sending the same record twice
  more by hand. The test then waits on a sentinel event on the same partition and asserts that
  only the original 2 notifications exist.

## 12. Trade-offs and limitations

- **Modular monolith instead of microservices.** A transfer, its ledger and its fraud decision
  need one ACID transaction. Splitting them would require sagas and compensations for no benefit
  at this scale. Kafka is used only where asynchrony is natural (notifications).
- **Pessimistic locking.** Correct and simple, but it serialises activity on hot accounts. At high
  volume you would shard, batch, or use a single-writer per account.
- **Stored balance plus ledger.** Fast balance reads, at the cost of keeping two representations
  in sync. They are always updated in the same transaction, and verification catches drift.
- **Held payments don't reserve funds.** A real system would place an authorisation hold.
- **HTTP Basic, in-memory users** for local development only. No OIDC, MFA or audit trail of
  operators beyond the reviewer name on alerts.
- **Outbox polling** adds latency of about the poll interval (1 s) and database load. CDC would
  remove both.
- No load testing has been done, and no throughput or latency numbers are claimed.
