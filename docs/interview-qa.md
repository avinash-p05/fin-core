# FinCore: interview questions and answers

Questions an interviewer is likely to ask about FinCore, from basic to advanced. Each answer is
written the way you would say it in an interview and points to where the code is. Performance
numbers come from [loadtest/RESULTS.md](../loadtest/RESULTS.md). The deeper concept explanations
are in [interview-guide.md](interview-guide.md).

**Contents**

1. [Pitch](#1-pitch)
2. [Basics: Java, Spring, money](#2-basics-java-spring-money)
3. [Database and transactions](#3-database-and-transactions)
4. [Concurrency and locking](#4-concurrency-and-locking)
5. [Idempotency](#5-idempotency)
6. [Ledger and accounting](#6-ledger-and-accounting)
7. [Refunds](#7-refunds)
8. [Fraud detection and Redis](#8-fraud-detection-and-redis)
9. [Kafka and the transactional outbox](#9-kafka-and-the-transactional-outbox)
10. [Performance and load testing (how the numbers were achieved)](#10-performance-and-load-testing)
11. [Security](#11-security)
12. [Testing](#12-testing)
13. [API design](#13-api-design)
14. [Frontend](#14-frontend)
15. [System design and scaling](#15-system-design-and-scaling)
16. [Trade-offs, mistakes and behavioural questions](#16-trade-offs-mistakes-and-behavioural-questions)
17. [Rapid-fire questions](#17-rapid-fire-questions)

---

## 1. Pitch

**Q: Describe FinCore in 30 seconds.**
FinCore is a simulated payments backend in Java 21 and Spring Boot. It moves INR between accounts
using a double-entry ledger in PostgreSQL. Every transfer is atomic, idempotent and protected
against race conditions. Fraud rules run before money moves, using Redis for velocity checks.
Committed payments are published to Kafka through a transactional outbox, and a downstream
consumer processes them idempotently. A React dashboard drives it. I load-tested it at about
1,000 payments per second on my laptop with the ledger still balanced afterwards.

**Q: Why did you build it?**
To practise the problems that make payments hard: double spending, duplicate requests, partial
failures and keeping a ledger consistent. A CRUD app doesn't exercise any of those.

**Q: What are you most proud of?**
The concurrency test caught a lost-update bug in my JPA locking. Ten concurrent withdrawals all
"succeeded" against a ₹500 balance. I found the root cause, a stale entity in the persistence
context, fixed it, and the same test now proves exactly 5 succeed (see "Tell me about the lost-update bug" in section 4).

---

## 2. Basics: Java, Spring, money

**Q: Why `BigDecimal` and not `double`?**
Binary floating point can't represent 0.1 exactly, so `0.1 + 0.2 != 0.3`. Money needs exact
decimal arithmetic. FinCore uses `BigDecimal` in Java and `NUMERIC(19,2)` in PostgreSQL.

**Q: How do you handle scale and rounding?**
`Money.normalize` calls `setScale(2, RoundingMode.UNNECESSARY)`. That **rejects** amounts with
more than two decimals (HTTP 400) instead of silently rounding them. Silent rounding is how money
leaks.

**Q: Why compare with `compareTo` instead of `equals` for BigDecimal?**
`new BigDecimal("1.0").equals(new BigDecimal("1.00"))` is false because the scale differs.
`compareTo` compares only the numeric value.

**Q: How do amounts appear in JSON and in Kafka events?**
In REST responses they are JSON numbers serialised from BigDecimal. In Kafka events they are
decimal **strings** (`"250.00"`), so no consumer ever parses money into a float.

**Q: What does `@Transactional` do internally?**
Spring wraps the bean in a proxy. The proxy opens a transaction (via `JpaTransactionManager`)
before the method runs, binds the connection to the thread, and commits on return or rolls back
on an unchecked exception.

**Q: What is the self-invocation pitfall?**
A call from one method to another method in the same bean skips the proxy, so the inner method's
`@Transactional` settings are ignored. FinCore avoids depending on that: the inner methods
(`lockInOrder`, `settle`) are only ever called inside an existing transaction.

**Q: What propagation levels do you use and why?**
- `REQUIRED` (the default) on service entry points.
- `MANDATORY` on methods that must never run on their own: `LedgerService.post`,
  `OutboxService.record`, `IdempotencyService.claim`, `PaymentService.settle`. Calling one
  outside a transaction throws immediately, which also guarantees that an outbox event can't be
  written outside its business transaction.

**Q: Why `spring.jpa.open-in-view=false`?**
Open-in-view keeps the database session open through view rendering. That allows hidden lazy
queries outside the service layer and holds connections longer. With it off, all database access
happens inside service transactions.

**Q: Why records for DTOs and classes for entities?**
Records are immutable and concise, which suits request and response data. JPA entities need a
no-argument constructor, mutable state and proxying, so they stay as classes.

**Q: Why does every entity extend `BaseEntity implements Persistable`?**
IDs are UUIDs assigned by the application. Without `Persistable`, Spring Data sees a non-null ID
and calls `merge`, which costs an extra SELECT and does **not** cascade PERSIST. That actually
broke the ledger entry inserts. `isNew()` makes `save()` call `persist`, which is a plain INSERT.

**Q: Why UUIDs instead of sequence IDs?**
The application can generate them before inserting (the payment ID goes into the idempotency row
and the outbox event inside the same transaction). They don't reveal volume, and they're safe to
generate across instances. The trade-off is a bigger, random-order index.

---

## 3. Database and transactions

**Q: Walk me through what happens in one `POST /payments`.**
Everything below is one PostgreSQL transaction (`PaymentService.create`):
1. Claim the idempotency key: `INSERT … ON CONFLICT DO NOTHING`.
2. Check that both accounts exist and are customer accounts.
3. Insert the payment row.
4. Fraud screening: high value (in memory), new beneficiary (SQL), velocity (Redis).
5. If REVIEW or DECLINED: set the status and insert a fraud alert. No money moves.
6. If APPROVED: `SELECT … FOR UPDATE` both accounts in sorted order, re-check frozen status and
   funds, update both balances, insert the ledger transaction and its 2 entries, mark the
   payment COMPLETED, insert the outbox event.
7. COMMIT. The deferred trigger verifies debits = credits at this point.

**Q: How does FinCore show each ACID property?**
- **Atomicity:** one transaction per payment. A failure anywhere rolls back the payment, the
  ledger, the balances, the outbox row and the idempotency key
  (`LedgerIntegrityTest.failureAfterTransferRollsBack…`).
- **Consistency:** CHECK constraints (no overdraft, refund bounds, positive amounts), foreign
  keys and triggers.
- **Isolation:** READ COMMITTED plus explicit row locks for writes, and REPEATABLE READ for
  verification.
- **Durability:** PostgreSQL's write-ahead log. Kafka only sees events after commit.

**Q: Which isolation level do you use?**
PostgreSQL's default, READ COMMITTED, with pessimistic row locks where it matters. Verification
and the dashboard use `REPEATABLE READ, readOnly`, so their several queries all see one snapshot.
Otherwise a transfer committing between two queries could show a false mismatch.

**Q: Why not just use SERIALIZABLE everywhere?**
It would be correct, but under contention PostgreSQL aborts transactions with serialisation
failures and the application has to retry them. Explicit locks on the two rows involved are
cheaper and predictable for this workload.

**Q: What constraints protect the data even if the Java code has a bug?**
- `CHECK (account_type = 'SYSTEM' OR balance >= 0)`: no overdraft.
- `CHECK (refunded_amount BETWEEN 0 AND amount)`: refunds bounded.
- `CHECK (amount > 0)` on payments, refunds and ledger entries.
- Unique keys on idempotency keys, alert per payment, processed events, and notification per
  event and account.
- Immutability triggers and the deferred double-entry trigger on the ledger.

**Q: What is a deferred constraint trigger and why use one here?**
`CREATE CONSTRAINT TRIGGER … DEFERRABLE INITIALLY DEFERRED` runs at COMMIT instead of after each
statement. A ledger transaction is inserted one entry at a time, so after the first entry it is
temporarily unbalanced. Checking at commit validates the final state.

**Q: How are schema changes managed?**
Flyway migrations V1 to V4 run at startup. Hibernate is set to `ddl-auto: validate`, so it checks
the entity mappings against the schema and never changes it.

**Q: Which indexes did you add and why?**
- `payments (source_account_id, created_at)` and `(destination_account_id, created_at)` for
  history.
- `(source, destination, status)` for the new-beneficiary rule.
- A **partial index** on `outbox_events (next_attempt_at) WHERE status='PENDING'`, so the relay
  only scans pending rows.
- `ledger_entries (account_id, created_at)` for account history.

---

## 4. Concurrency and locking

**Q: Two withdrawals hit the same account at the same time. What happens?**
Both transactions run `SELECT … FOR UPDATE` on the account. The second one blocks until the first
commits, then reads the new balance and checks funds again. `PaymentConcurrencyTest` sends 10
parallel ₹100 withdrawals from a ₹500 account: exactly 5 complete, 5 fail with insufficient funds,
and the final balance is ₹0.

**Q: How do you prevent deadlocks?**
The two accounts are always locked in the same order: sorted by UUID string, which matches
PostgreSQL's UUID ordering. A→B and B→A both lock the lower ID first, so neither can wait on the
other in a cycle. The test runs 16 opposite-direction transfers concurrently and all complete.

**Q: Pessimistic or optimistic locking? Why?**
Pessimistic for balances. On a busy account conflicts are expected. Optimistic locking would turn
them into failed transactions that the application must retry, and retrying a money movement is
riskier than waiting a few milliseconds for a lock. `@Version` exists on payments and alerts as a
safety net.

**Q: Tell me about the lost-update bug.** *(very likely question)*
The account repository had an `@Lock(PESSIMISTIC_WRITE)` query, so the row was locked. But
earlier in the same transaction the account had already been loaded with `findById` for
validation. Hibernate's persistence context is an identity map: the locking query returned the
**already-cached, stale** entity without refreshing it. The code subtracted from an old balance,
and the flush overwrote the other transaction's update. The concurrency test showed 10/10
withdrawals succeeding from ₹500.

The fix is `RowLocks.lockForUpdate`, which calls `entityManager.refresh(entity, PESSIMISTIC_WRITE)`.
That issues `SELECT … FOR UPDATE` **and** overwrites the cached state with the locked row. The
lesson: in JPA, "I locked the row" doesn't mean "my object is current".

**Q: What would have caught it if the test hadn't?**
The database CHECK `balance >= 0` would have rejected the overdraft. But that only stops
overdrafts, not lost updates on credits, so the test was the real safety net.

**Q: How do you stop two analysts approving the same held payment?**
The alert row is locked first. The second analyst waits, then sees a final status and gets 409
`INVALID_ALERT_TRANSITION`. A test runs 5 concurrent approvals: exactly 1 succeeds.

**Q: How do you stop two concurrent refunds over-refunding?**
The payment row is locked for the whole refund. 6 concurrent full refunds give exactly 1 success.

**Q: What is the lock timeout?**
5 seconds (`jakarta.persistence.lock.timeout`), so a stuck transaction can't hold a request
forever.

**Q: What's the cost of this design?**
All writes to one account are serialised. The hot-account load test measured that ceiling at
about **265 payments/s for a single account** (p95 297 ms) on a laptop. See the hot-account question in section 15 for how to
raise it.

---

## 5. Idempotency

**Q: What is idempotency and why does a payment API need it?**
Repeating an operation has the same effect as doing it once. Clients retry after timeouts, and
without idempotency a retry can charge twice.

**Q: How is it implemented?**
The client sends an `Idempotency-Key` header. The server computes a SHA-256 **fingerprint** of
the canonical request (accounts, amount normalised to 2 decimals, currency, description) and runs
`INSERT INTO idempotency_keys … ON CONFLICT DO NOTHING` inside the payment transaction.
- Row inserted → new request: execute it.
- Conflict with the same fingerprint → return the stored payment with HTTP 200 and
  `Idempotent-Replayed: true`.
- Conflict with a different fingerprint → 409 `IDEMPOTENCY_KEY_REUSED`.

**Q: Two identical requests arrive at the same millisecond. What happens?**
The second INSERT blocks on the primary-key index until the first transaction finishes. If the
first committed, the second sees the conflict and replays. If the first rolled back, the second
claims the key and executes. A test with 8 parallel same-key requests produces 1 execution.

**Q: Why store the key in PostgreSQL rather than Redis?**
It must commit **atomically** with the money movement. With Redis there's a window where the key
is saved but the payment rolled back, or the reverse. Redis can also lose data on restart.

**Q: Why fingerprint the payload?**
To catch a client bug that reuses a key for a different payment. Silently replaying the old
payment would hide a real ₹200 transfer behind a stale ₹100 one.

**Q: Is a failed payment replayed?**
Yes. Insufficient funds is stored as `FAILED` and replayed. Validation errors (400/404) roll back,
key included, so the client can fix the request and retry with the same key.

**Q: Limitations?**
- A replay returns the payment's **current** state, which may have changed (for example to
  REFUNDED), not a byte-for-byte copy of the original response.
- Keys are never expired. Production would add a TTL, such as 24 hours.

---

## 6. Ledger and accounting

**Q: What is double-entry bookkeeping?**
Every movement is recorded as at least one debit and at least one credit of equal total. Money
can't appear or disappear: Σ debits = Σ credits always.

**Q: What's your sign convention?**
For every account, **balance = Σ credits − Σ debits**. Customer accounts are deposits, which are
liabilities of the bank, so a credit increases the customer's balance. A transfer debits the payer
and credits the payee.

**Q: How do opening balances work without breaking the invariant?**
A system funding account is debited and the new account is credited (`OPENING_BALANCE`). The
system account is the only one allowed to go negative. Σ of all balances stays 0.

**Q: Why store a balance column if the ledger is the source of truth?**
Reading a balance should be O(1), not a sum over every entry. The column is only ever changed in
the same transaction as the ledger entries, and verification recomputes it from the ledger to
catch drift.

**Q: How do you verify the ledger?**
`GET /ledger/verification`, in a REPEATABLE READ snapshot, checks:
- every transaction has at least 2 entries and debits = credits;
- every account's stored balance equals its ledger sum;
- every settled payment has exactly one TRANSFER transaction;
- global debit total = global credit total.

After about 97,000 load-test payments it reported VALID across 171,186 entries.

**Q: How are posted entries made immutable?**
`BEFORE UPDATE OR DELETE` triggers raise an exception, and the entities are Hibernate
`@Immutable`. Tests check that both UPDATE and DELETE fail.

**Q: How would you correct a wrong entry?**
Never edit it. Post a new compensating transaction. That's exactly how refunds work.

---

## 7. Refunds

**Q: How does a refund work?**
It posts a new `REFUND` ledger transaction that debits the original payee and credits the
original payer, linked by `refund_id`. The original TRANSFER is untouched. The payment's
`refunded_amount` increases and its status becomes PARTIALLY_REFUNDED or REFUNDED.

**Q: How do you prevent over-refunding?**
The payment row is locked, then `amount ≤ refundable` is checked, and a CHECK constraint enforces
the bound in the database too.

**Q: What if the payee has already spent the money?**
The refund is rejected with 422 `INSUFFICIENT_FUNDS`. FinCore never makes a customer balance
negative. Real systems may allow negative balances or recover the money separately.

**Q: Are refunds idempotent?**
Yes, they use the same key mechanism with scope `REFUND`. Reusing a payment's key for a refund
gives 409.

---

## 8. Fraud detection and Redis

**Q: What rules are implemented?**

| Rule | Trigger | Score |
|---|---|---|
| HIGH_VALUE | amount ≥ ₹1,00,000 | 50 |
| VELOCITY | more than 5 attempts per source account in 60 s | 40 |
| NEW_BENEFICIARY | no previously settled payment from this source to this destination | 20 |

Scores add up. 40 or more means REVIEW and 70 or more means DECLINED. All of it is configurable
and validated at startup.

**Q: Why run fraud checks before the transfer, synchronously?**
The decision has to gate authorisation. If it ran asynchronously after commit, the money would
already have moved, and you'd need claw-backs.

**Q: What happens on REVIEW?**
The payment becomes `PENDING_REVIEW` and an alert is created. There's no debit and no ledger
entry. When an analyst approves, the transfer runs and funds are re-checked under lock, so the
payment can still end `FAILED`. Rejecting sets it to `DECLINED`.

**Q: How does velocity detection work in Redis?**
Key `fincore:velocity:{accountId}`. A Lua script does `INCR` and, on the first increment,
`PEXPIRE`.

**Q: Why Lua instead of separate INCR and EXPIRE calls?**
Redis runs a script atomically. With two separate calls, a crash between them could leave a key
with no TTL, which would block that account forever. It is also one network round trip instead
of two. A test with 50 concurrent increments sees counts 1 to 50 exactly once each.

**Q: What if Redis is down?**
The client times out after 500 ms. The rule records `VELOCITY_CHECK_UNAVAILABLE` and raises the
decision to at least REVIEW (configurable to DECLINED). Configuring APPROVED for this case fails
validation at startup. I tested this by stopping Redis: the payment came back `PENDING_REVIEW` in
under a second, and payments were approved again once Redis was back.

**Q: Why fail closed rather than fail open?**
Fraud is a mandatory control. Failing open would let attackers bypass it by overloading Redis.
Holding payments for review keeps the service available while staying safe.

**Q: What are the weaknesses of a fixed window?**
A burst that straddles a window boundary can reach almost twice the limit. A sliding-window log
(sorted set) or a token bucket would fix it. Also, the counter increments even if the database
transaction rolls back. Over-counting is the safe direction for a fraud control.

**Q: Why is new-beneficiary checked in SQL rather than Redis?**
Beneficiary history is durable business data, and PostgreSQL is the source of truth for it.

---

## 9. Kafka and the transactional outbox

**Q: What problem does the outbox solve?**
The dual write. If you commit to the database and then publish to Kafka, a crash in between loses
the event. If you publish first and the commit then fails, you've sent an event for something
that never happened.

**Q: How does FinCore's outbox work?**
1. The event row is inserted into `outbox_events` **in the same transaction** as the payment
   (`Propagation.MANDATORY`).
2. A scheduled relay claims due rows with `FOR UPDATE SKIP LOCKED`, sends them to Kafka (key =
   payment ID, header = event ID), and marks them PUBLISHED.
3. On failure it backs off exponentially (1 s × 2^n, capped at 5 min) and parks the row as
   `FAILED` after 10 attempts.

**Q: Why `SKIP LOCKED`?**
So several relay instances can run in parallel without blocking on, or double-claiming, the same
rows.

**Q: What delivery guarantee do you provide?**
**At-least-once.** If the process crashes after Kafka acknowledged a record but before PUBLISHED
was committed, the record is sent again. The producer uses `enable.idempotence=true`, which only
de-duplicates the producer's *own* retries, not an outbox re-send.

**Q: Is it exactly-once?**
No, and I don't claim it is. Kafka's exactly-once transactions cover Kafka-to-Kafka processing,
not writes to PostgreSQL. The practical answer is at-least-once delivery plus an idempotent
consumer, which gives an exactly-once **effect**.

**Q: How is the consumer idempotent?**
In one database transaction it inserts `processed_events(consumer, event_id) ON CONFLICT DO
NOTHING`. If nothing was inserted, the event is a duplicate and is skipped. Otherwise it writes
its notifications. A failure rolls back both, so a retry starts clean. A unique key on
`(event_id, account_id)` guards the effect too. Tested by re-publishing an outbox row and sending
the same record twice more by hand: still exactly 2 notifications.

**Q: What are the retry and dead-letter settings?**
`DefaultErrorHandler` retries 3 times, 500 ms apart, then `DeadLetterPublishingRecoverer`
publishes to `fincore.payment-events.DLT`. Malformed JSON isn't retried because retrying can't
fix it. Tests cover both the poison-message and the retries-exhausted cases.

**Q: Is ordering guaranteed?**
Per partition, and the key is the payment ID, so a payment's events share a partition. But if
one event is waiting in backoff, a later event for the same payment can overtake it. The consumer
handles each event independently. Strict ordering would need per-aggregate sequencing.

**Q: When are offsets committed?**
`ack-mode: record`: after each record has been processed, which happens after its database
transaction committed. A crash between the two causes a redelivery, which de-duplication absorbs.

**Q: Why polling and not CDC (Debezium)?**
Polling is simple and has no extra infrastructure. CDC reads the write-ahead log, which gives
lower latency and no polling load, but adds Kafka Connect and Debezium to operate.

**Q: Why is the consumer in the same JVM?**
For simplicity. It only touches its own tables and the event contract, so moving it to its own
process is a deployment change, not a code change.

---

## 10. Performance and load testing

> Numbers from [loadtest/RESULTS.md](../loadtest/RESULTS.md): one laptop (Ryzen 7 6800HS), all
> services in Docker, k6 on the same machine, 50 virtual users for 60 s after warm-up.

**Q: What throughput did you achieve?**
- **About 1,000 payments/s at 87 ms p95** (61,143 payments in 60 s, 0 errors) with authentication
  cost reduced.
- **About 900/s at 102 ms p95** with the outbox relay publishing to Kafka at the same time.
- **About 161/s** with the default dev authentication. the bottleneck question below explains why.
- **About 265/s on a single hot account**, which is the lock-serialisation ceiling.

**Q: How did you measure it?**
A k6 script (`loadtest/payments.js`) creates 100 accounts through the real API, then runs 50
concurrent users, each sending its next request as soon as the previous one returns. Every
request is a full payment: auth, idempotency claim, fraud rules including the Redis call, two row
locks, ledger posting and the outbox insert. I warmed the JVM up for 15 s first, took
percentiles (p50/p95/p99, not only averages), and checked that the ledger verified as VALID
afterwards. A fast but wrong payment system is worthless.

**Q: What was the first bottleneck, and how did you find it?**
The first run gave only 161 payments/s with p50 = 289 ms, which was suspicious for a few small SQL
statements. To isolate it I load-tested `GET /api/v1/me`, which does nothing except
authenticate. It also capped at about 186 req/s with a 100 ms median. So the cost was
**authentication**, not payments.

HTTP Basic sends the password on every request, and Spring Security verifies it with **BCrypt
cost 10 every time**. BCrypt is deliberately slow (2^10 rounds) to resist brute force. Lowering
the cost to 4 for the benchmark took throughput from 161 to about 1,019/s and p95 from 595 ms to
87 ms. That's a **6.3× improvement from one variable**.

**Q: So in production you'd lower BCrypt's cost?**
No. A low cost factor weakens password storage. The real fix is to **stop verifying a password on
every request**. Authenticate once, then use a signed token (JWT/OIDC, verified in microseconds)
or a server session. Lowering the cost in the load test was a way to measure what the
payment path itself can do, and I label it that way.

**Q: What other optimisations did you make?**
1. **Pipelined outbox relay: 133 → over 2,800 events/s (about 21×).** The relay originally sent
   one record and waited for Kafka's acknowledgement (`send().get()`) before the next, so each
   event paid a full network round trip. Under 1,000 payments/s the backlog grew to 51,000
   events. Now it hands the whole batch to the producer first, so the producer can batch records
   onto the wire, and then awaits the acknowledgements. The batch size went from 50 to 200, and
   Hibernate JDBC batching (`batch_size: 50`, ordered updates) sends the status updates in groups.
   During the next run the outbox ended at **0 pending**.
2. **Consumer concurrency: about 530 → about 1,049 events/s (about 2×).** The topic has 3
   partitions but the listener had 1 thread. Setting `concurrency: 3` gives one thread per
   partition. That's safe because de-duplication is per event and ordering is per partition.
3. **Avoiding wasted queries:** `Persistable` makes inserts plain INSERTs instead of
   SELECT + INSERT (merge). Redis velocity is a single Lua round trip. A partial index keeps the
   outbox scan small.
4. **Spreading load across accounts:** throughput depends on how many different accounts are
   involved. 100 accounts → about 1,000/s; 1 hot account → about 265/s.

**Q: Why does a single account top out at about 265/s?**
Every payment from that account must hold its row lock for the rest of its transaction: balance
update, ledger inserts, outbox insert, commit (fsync). Those transactions run strictly one after
another. 1 s ÷ 265 ≈ 3.8 ms per transaction, which is roughly the cost of the locked section plus
commit. Adding servers doesn't help a single hot row; see the hot-account question in section 15.

**Q: Did correctness hold under load?**
Yes. After about 97,000 payments:
- ledger verification VALID across 85,593 transactions and 171,186 entries, debits = credits;
- 0 balance mismatches and 0 negative balances;
- the hot account reconciled to exactly ₹0.51 after 3,998 debits, and the other 11,885 payments
  failed cleanly with insufficient funds;
- the consumer wrote exactly 2 notifications per event, so no duplicates.

**Q: How much database work does one payment do?**
About 15 statements in one transaction:
- idempotency insert and later update;
- account existence checks;
- payment insert and later update;
- beneficiary query;
- 2 lock-and-refresh reads;
- 2 balance updates;
- ledger transaction plus 2 entries;
- outbox insert;
- the deferred trigger check at commit.

Plus one Redis round trip. With 50 users and a 20-connection Hikari pool, the pool is part of the
ceiling.

**Q: What's wrong with your benchmark?** *(interviewers like candour)*
- k6 runs on the same machine as the servers, competing for CPU.
- Every run was a single 60 s measurement, not averaged over repeats.
- The load is a closed loop with 50 users, not a fixed arrival rate, so it doesn't show how
  latency behaves at a target request rate.
- Laptop and Docker Desktop, not production hardware.
- The velocity threshold was relaxed so payments settle (the Redis call still runs).

The numbers show relative effects and bottlenecks, not production capacity.

**Q: What would you optimise next?**
- Token auth instead of Basic plus BCrypt.
- Remove the duplicate account read (validate and lock in one step).
- Profile with JFR or async-profiler instead of reasoning from latency.
- Tune the Hikari pool against PostgreSQL's `max_connections`.
- Run a fixed-arrival-rate test to find the saturation point.
- Batch the consumer's inserts.
- Hot accounts: see the hot-account question in section 15.

---

## 11. Security

**Q: How does authentication work?**
Stateless HTTP Basic with two in-memory users: `OPERATOR`, and `ANALYST`, which adds fraud-review
rights. Passwords are BCrypt-hashed. It's documented as **local-development only**.

**Q: What's wrong with it for production?**
- Credentials travel on every request.
- BCrypt verification on every request (the performance issue above).
- No user store, password rotation, MFA, lockout or login audit.

Production would use an identity provider (OIDC) and Spring's OAuth2 resource server with JWT.

**Q: How is authorisation enforced?**
URL rules in `SecurityConfig`: `PATCH /fraud/alerts/**` needs ANALYST, and the rest of `/api/**`
needs OPERATOR or ANALYST. A test checks that an operator gets 403 on review actions.

**Q: Why is CSRF disabled?**
The API is stateless and authenticated by an `Authorization` header, not cookies. CSRF works by
abusing cookies the browser sends automatically, so the attack doesn't apply.

**Q: How is CORS handled?**
The browser talks to a single origin: Vite's proxy in development, nginx in Docker. CORS is still
restricted to configured origins for direct calls.

**Q: How do errors avoid leaking internals?**
RFC 9457 problem details with stable codes. Database constraint messages are logged, not
returned, and stack traces are never included in responses.

**Q: What does `/me` filter, and why?**
Spring Security 7 also grants factor authorities such as `FACTOR_PASSWORD`. `/me` exposes only
`ROLE_*` authorities to the UI.

---

## 12. Testing

**Q: What's your testing strategy?**
65 automated tests:
- **Unit tests** for pure logic: fraud scoring, outbox backoff, configuration validation.
- **Integration tests** against real PostgreSQL 16, Redis 7 and Kafka 3.8 via Testcontainers, for
  everything involving transactions, locks, constraints and messaging.

Plus the k6 load test and manual end-to-end checks in the browser.

**Q: Why Testcontainers instead of H2 or mocks?**
The behaviour I care about only exists in the real systems: `SELECT FOR UPDATE`, `ON CONFLICT`,
deferred triggers, `SKIP LOCKED`, JSONB, Redis Lua, Kafka redelivery. H2 would pass tests that
fail in production.

**Q: How do you test concurrency reliably?**
A helper (`Concurrently`) starts N threads, waits until all are ready, then releases them at once
with a latch, which maximises contention. Assertions check invariants (exactly 5 succeed, balance
₹0) rather than timing.

**Q: How do you test something that should NOT happen, like a duplicate Kafka effect?**
Send the duplicates, then send a sentinel event on the same partition and wait for it to be
processed. Because a partition is ordered, the duplicates were handled before the sentinel. Then
assert there are still exactly 2 notifications. That avoids arbitrary sleeps.

**Q: How did you test the Redis outage?**
At three levels:
- a unit test with a mocked tracker that throws;
- an integration test pointing a real Lettuce client at a closed port, to check the exception
  translation and the timeout;
- a full payment-path test with the tracker replaced by a failing mock.

Plus a manual run with the Redis container stopped.

**Q: How do you keep integration tests fast?**
- The containers are JVM-wide singletons shared by every test class.
- One cached Spring context for almost all classes.
- Every test creates its own accounts, so nothing needs cleanup.
- The outbox scheduler is off in tests, which drive the publisher directly.

**Q: Which test found a real bug?**
`PaymentConcurrencyTest` found the stale-entity lost update (section 4, lost-update bug). Writing the
`LedgerIntegrityTest` exposed that `save()` was merging instead of persisting.

---

## 13. API design

**Q: Why do insufficient funds return 201 with `status: FAILED` rather than a 4xx?**
The request was valid and was processed. The business outcome is persisted and replayable for the
same key, and the client gets a payment ID it can look up. 4xx codes are for invalid requests
(400, 404, 409, 422 for misuse such as system accounts).

**Q: 201 vs 200 on replay?**
201 means this request created the payment. 200 plus `Idempotent-Replayed: true` means the result
was produced by an earlier request.

**Q: Why is pagination capped?**
To stop unbounded queries: `size` is between 1 and 200, and sorting is stable
(`createdAt desc, id`).

**Q: How is the API documented?**
OpenAPI 3 through springdoc, with Swagger UI at `/swagger-ui.html`.

---

## 14. Frontend

**Q: What's the frontend's responsibility?**
Display and input only. Every number, verification result and fraud decision comes from the
backend. Even the "insufficient funds" demo sends a real request and shows the backend's answer.

**Q: How is API access structured?**
A single typed client (`services/api.ts`) handles auth headers, the idempotency header, query
building and conversion of problem details into `ApiError`. TypeScript types mirror the DTOs.

**Q: How does it handle async state?**
A small `useAsync` hook provides loading, error and empty states, plus a request counter so a
stale response can't overwrite a newer one.

**Q: How does INR formatting work?**
`Intl.NumberFormat('en-IN', {currency: 'INR'})` gives lakh/crore grouping: ₹1,20,000.00.

---

## 15. System design and scaling

**Q: How would you scale to 10× traffic?**
- Run multiple stateless app instances behind a load balancer. Locks live in PostgreSQL, so that's
  safe.
- Replace Basic auth with tokens.
- Add PgBouncer for connection pooling and read replicas for history and dashboards.
- Run several relay instances (`SKIP LOCKED`).
- Add Kafka partitions and consumer instances.

**Q: When would one PostgreSQL instance stop being enough?**
When write throughput or storage outgrows a single primary. Then shard accounts by account ID, and
handle cross-shard transfers with a saga plus a ledger clearing account, or a dedicated ledger
database such as TigerBeetle.

**Q: How would you handle a hot account, like a merchant receiving thousands of payments a
second?**
- Split it into N sub-accounts and spread credits across them, summing them for the balance.
- Or credit asynchronously: append ledger entries and update the balance in batches.
- For debits: reserve a balance in chunks.

All of these trade simplicity for throughput.

**Q: Why a modular monolith and not microservices?**
A transfer, its ledger entries and its fraud decision must be one ACID transaction. Splitting them
across services means sagas, compensations and eventual consistency for no benefit at this scale.
Kafka is used only where asynchrony is natural.

**Q: How would you add multi-currency?**
- A currency on every account and entry, with balancing per currency.
- FX as a separate ledger transaction through FX accounts, with the rate stored.
- Never mix currencies in one sum.

**Q: How would you implement authorisation holds for REVIEW payments?**
Add an `available_balance` (or a holds table). On REVIEW, reserve the amount under the account
lock. On approval, convert the hold into a debit; on rejection or expiry, release it.

**Q: How would you monitor it in production?**
- Micrometer metrics: payment outcome counts, latency, outbox pending and FAILED counts and the
  age of the oldest pending event, consumer lag, DLT size, Redis outage count.
- Alerts on any ledger-verification failure.
- Trace IDs carried from HTTP into Kafka headers.

**Q: How would you replay the DLT or FAILED outbox events?**
An admin endpoint or tool that resets `FAILED` → `PENDING` (outbox) or republishes DLT records
after a fix. Both are safe because consumers are idempotent. This isn't built yet, and the README
says so.

---

## 16. Trade-offs, mistakes and behavioural questions

**Q: What would you do differently?**
- Token auth from the start.
- Authorisation holds.
- A sliding-window velocity limit.
- Per-aggregate event ordering.
- An admin replay tool.
- Load testing earlier: it found two real bottlenecks (auth, and the synchronous outbox) that
  functional tests couldn't.

**Q: Tell me about a mistake you made.**
I trusted `@Lock(PESSIMISTIC_WRITE)` to give me current data. It locked the row but returned a
cached entity. I only caught it because I wrote a real concurrency test against real PostgreSQL
instead of mocking the repository (section 4, lost-update bug).

**Q: Tell me about a performance investigation.**
The first load test gave 161 payments/s at 289 ms median. Instead of guessing, I load-tested an
endpoint that only authenticates, saw the same ceiling, and identified per-request BCrypt as the
cause. Then I found the outbox couldn't keep up (51k backlog, 133 events/s) and pipelined the
sends (over 2,800/s). Then the consumer was the slowest stage, and matching listener threads to
partitions doubled it. Each step: measure, find the bottleneck, change one thing, measure again.

**Q: What are the known limitations?**
- Dev-only auth.
- Fixed-window velocity.
- No fund holds for REVIEW payments.
- Possible event reordering during backoff.
- No admin replay tool.
- Replays return current state.
- Single currency.
- Laptop-only benchmarks.

All of these are listed in the README.

**Q: How did you make sure you didn't over-engineer?**
Each piece has a concrete failure it prevents. Locks prevent double spending, idempotency prevents
double charging, and the outbox prevents lost events. No microservices, no service mesh, no extra
libraries beyond what those needed.

---

## 17. Rapid-fire questions

| Question | Short answer |
|---|---|
| Money type? | `BigDecimal` / `NUMERIC(19,2)` |
| Rounding mode? | `UNNECESSARY`: reject more than 2 decimals |
| Lock type? | `SELECT … FOR UPDATE` via `refresh(PESSIMISTIC_WRITE)` |
| Deadlock prevention? | Lock in sorted UUID order |
| Idempotency storage? | PostgreSQL, same transaction as the payment |
| Same key, different body? | 409 |
| Ledger invariant? | Σ debits = Σ credits, deferred trigger at COMMIT |
| Balance formula? | Σ credits − Σ debits |
| Can a ledger row be edited? | No: trigger plus `@Immutable` |
| Refund mechanism? | New compensating REFUND transaction |
| Fraud thresholds? | ≥ 40 REVIEW, ≥ 70 DECLINED |
| Redis down? | REVIEW (fail closed), 500 ms timeout |
| Why Lua? | Atomic INCR + EXPIRE in one round trip |
| Outbox claim query? | `FOR UPDATE SKIP LOCKED` |
| Delivery guarantee? | At-least-once + idempotent consumer |
| DLT after? | 3 retries, 500 ms apart; poison messages immediately |
| Consumer threads? | 3 = partitions |
| Peak throughput? | ≈1,000 payments/s, p95 87 ms (laptop, reduced BCrypt) |
| Single-account ceiling? | ≈265/s |
| Outbox relay speed-up? | 133 → over 2,800 events/s (pipelining) |
| Biggest bottleneck found? | BCrypt on every HTTP Basic request |
| Tests? | 65: unit + Testcontainers integration |
