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
