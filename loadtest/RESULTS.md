# FinCore load test results

Run on **2026-10-17** with k6 against the Docker Compose stack.

These are single-laptop numbers, not production capacity. k6, the backend, PostgreSQL, Redis,
Kafka and the consumer all shared one machine, so they competed for the same CPU and disk.

## Environment

| | |
|---|---|
| Machine | AMD Ryzen 7 6800HS (8 cores / 16 threads), 16 GB RAM, Windows 11 |
| Docker VM | 16 vCPU, ~7.4 GB RAM (Docker Desktop 29.7) |
| Stack | `infrastructure/docker-compose.yml` + `loadtest/docker-compose.loadtest.yml` |
| Backend | Spring Boot 4.1, Java 21, Hikari pool 20, PostgreSQL 16, Redis 7, Kafka 3.8 (1 broker, 3 partitions) |
| Load | k6 v1.2.2, `constant-vus` with 50 virtual users for 60 s, after a 15 s warm-up run |
| Script | [`payments.js`](payments.js) sends `POST /api/v1/payments` with a unique `Idempotency-Key` per request, ₹1–₹500.99 |

Load-test override:

- **The velocity limit is raised to 1,000,000.** The velocity rule still runs a Redis Lua call on
  every payment. Only the threshold changes, so that payments settle instead of going to REVIEW.
- **The BCrypt strength (`FINCORE_BCRYPT_STRENGTH`) differs per run**, as listed below. The
  default is 10.

Every request runs the full path:

1. HTTP Basic authentication
2. Idempotency claim
3. Fraud scoring: new-beneficiary SQL query and Redis velocity call
4. Locking two accounts
5. Ledger posting
6. Outbox insert
7. Commit, including the deferred double-entry trigger

## Results

| Run | Scenario | BCrypt | Payments in 60 s | Throughput | p50 | p95 | p99 | HTTP errors |
|---|---|---|---|---|---|---|---|---|
| A | 100 accounts, random pairs | 10 (default) | 9,688 | ~161/s | 289 ms | 595 ms | 710 ms | 0 |
| B | 100 accounts, random pairs | 4 | 61,143 | ~1,019/s | 45 ms | 87 ms | 115 ms | 0 |
| C | **one hot payer account** | 4 | 15,883 | ~265/s | 167 ms | 297 ms | 442 ms | 0 |
| D | as B, pipelined outbox | 4 | 54,127 | ~902/s | 50 ms | 102 ms | 161 ms | 0 |
| E | as D, consumer concurrency 3 (final) | 4 | 51,957 | ~866/s | 54 ms | 103 ms | 137 ms | 0 |

Throughput is payment requests divided by the 60 s load phase. Raw k6 JSON summaries are in
[`results/`](results/).

### Observations

1. **HTTP Basic + BCrypt was the bottleneck (A vs B).** Basic auth runs BCrypt on every request.
   A trivial authenticated `GET /api/v1/me` peaked at ~186 req/s with a 100 ms median at strength
   10. Lowering the cost factor raised payment throughput about 6× (161/s → ~1,019/s) and cut
   p95 from 595 ms to 87 ms. Production would use token authentication (for example JWT signature
   checks), which costs microseconds rather than milliseconds. This is one more reason the README
   marks Basic auth as dev-only.
2. **Lock contention on one account is the scaling limit (C).** When all 50 users debit the same
   account, the `SELECT … FOR UPDATE` lock serialises them. That gives ~265/s, about a quarter of
   the spread-out throughput. The account (₹10,00,000) ran out after **3,998** debits. The other
   **11,885** payments came back `FAILED / INSUFFICIENT_FUNDS`. The final balance was ₹0.51 and it
   reconciled exactly with the ledger. **No overdraft happened under contention.**
3. **The outbox relay couldn't keep up at first (B → D).** It waited for each Kafka
   acknowledgement before sending the next record, which drained only **~133 events/s**. After
   B, 51,317 events were waiting. Pipelining the batch (send all records first, then await the
   acks), with a 200-row batch size and Hibernate JDBC batching, raised the drain rate to
   **>2,800 events/s**: a backlog of 28,467 cleared in under 10 s, so 2,800/s is a lower bound.
   During runs D and E, **0 events were pending** when the load stopped.
4. **The consumer catches up after the load stops (D → E).** With one listener thread it processed
   ~530 events/s. With `concurrency: 3` (one thread per partition) it processed ~1,041 events/s on
   a backlog. During peak load in E it competed with the payment path for the same database and
   CPU, so it fell behind. It finished all events **46 s after the load ended** (108 s after the
   run started). Running the consumer as a separate deployment with its own resources would
   remove this coupling.

## Integrity after all runs

These checks ran after runs A–E plus warm-ups, against the same database:

| Check | Result |
|---|---|
| Payments created | 211,878 (199,993 COMPLETED, 11,885 FAILED) |
| Ledger verification (`GET /api/v1/ledger/verification`) | **VALID**: 200,563 transactions, 401,126 entries |
| Σ debits vs Σ credits | ₹62,01,51,423.41 = ₹62,01,51,423.41 |
| Unbalanced transactions / balance mismatches / settled payments without ledger | 0 / 0 / 0 |
| Negative customer balances | 0 |
| Completed payments without an outbox event | 0 |
| Outbox events published | 199,993 (0 pending, 0 failed) |
| Events processed by the consumer | 199,993 |
| Notifications | 399,986, exactly 2 per event, 0 duplicates |

## Reproduce

```bash
docker compose -f infrastructure/docker-compose.yml -f loadtest/docker-compose.loadtest.yml up -d --build
# For runs B-E, start the backend with a lower BCrypt cost first:
#   FINCORE_BCRYPT_STRENGTH=4 docker compose -f infrastructure/docker-compose.yml -f loadtest/docker-compose.loadtest.yml up -d backend
k6 run -e SCENARIO=warmup -e VUS=20 -e DURATION=15s -e ACCOUNTS=10 loadtest/payments.js
k6 run -e SCENARIO=spread -e VUS=50 -e DURATION=60s loadtest/payments.js
k6 run -e SCENARIO=hot    -e VUS=50 -e DURATION=60s loadtest/payments.js
```

Expect different numbers on different hardware. Re-run on your own machine before quoting them.
