# Plan: AI fraud-review copilot (Spring AI)

Goal: one finished, measured AI feature in FinCore, so the Java project also covers "LLM integration / AI-assisted / evaluation" asks in Java roles (SAP Concur AI, Cisco, Apple ETS, Amex, fintech).
Rule: nothing goes on the resume until it runs and the eval numbers exist.

## Why this feature
FinCore already holds payments in `REVIEW` and raises a `fraud_alerts` row (rules: HIGH_VALUE, NEW_BENEFICIARY, VELOCITY, VELOCITY_CHECK_UNAVAILABLE). An analyst approves or rejects through `FraudReviewService.updateStatus` (row-locked).
The copilot helps that analyst: it gathers context with tools and writes a grounded case summary plus a suggested action. **It never approves or rejects anything** — the human decision path stays exactly as it is. That's the story interviewers like: LLM in a money system, with guardrails.

## Scope (day 1 = must, day 2 = should)

### Day 1: the copilot
1. **Dependency.** Add Spring AI (BOM + one model starter: Anthropic or OpenAI). FinCore is on **Spring Boot 4.1.1 / Java 25**, so pick the Spring AI line that supports Boot 4 (check the Spring AI docs' compatibility table before adding it; the 1.x line targets Boot 3). Keys go in env vars, not `application.yml`.
2. **Read-only tools** (`@Tool` methods in a new `com.fincore.ai` package, each calling existing services/repos):
   - `getAlert(alertId)` → score, decision, triggered rules, status
   - `getPayment(paymentId)` → amount, accounts, status, fraud fields
   - `getRecentPayments(accountId, days)` → last N payments for the source account
   - `hasPriorPayments(source, destination)` → beneficiary history
   - `getAccountSummary(accountId)` → type, status, age, balance
3. **Endpoint.** `POST /api/v1/fraud/alerts/{alertId}/copilot` → JSON `{ summary, riskFactors[], suggestedAction: APPROVE|REJECT|ESCALATE, confidence, citedPaymentIds[] }`. Use Spring AI structured output (map to a Java record).
4. **Guardrails** (the important part):
   - Tools are read-only; no tool can change state.
   - **Grounding check** after the LLM call: every `citedPaymentId` must exist and belong to the accounts in the alert; amounts quoted must match the DB. If the check fails → return `ESCALATE` with "copilot answer failed validation" (fail closed, same idea as the velocity rule).
   - Timeout + max tool calls; on LLM error → 503 with no suggestion, never a guess.
   - Log each call: alert id, model, tool calls, latency, tokens, validation result (Actuator/Micrometer metrics: `copilot.latency`, `copilot.tokens`, `copilot.validation.failed`).
5. **Tests.** Unit test the grounding validator. One Testcontainers integration test with a mocked `ChatModel`.

### Day 2: evaluation (this gives the resume numbers)
6. **Eval dataset.** Seed 30–50 alerts with known "right" answers (extend `DemoDataSeeder` or a test fixture): e.g. 1st payment of 4.9L to a new beneficiary at 2 a.m. after 6 payments in 10 min → REJECT/ESCALATE; repeat payee, high value, long history → APPROVE.
7. **Eval runner** (JUnit tagged `eval` or a small CLI): run the copilot on every case and record
   - suggested-action agreement with the label (%)
   - grounding pass rate (% of answers whose citations/amounts all validate)
   - tool-call correctness (did it call the tools it needed)
   - p50/p95 latency, average tokens/cost per alert
   Save results to `docs/copilot-eval.md`.
8. **Frontend (optional).** A "Copilot" panel on the alert detail page showing the summary and cited payments.

### Stretch (only if time)
- pgvector RAG over `docs/` + fraud-rule descriptions so the copilot can explain *why* a rule exists (Spring AI `VectorStore` + PgVector). Adds "semantic retrieval" honestly.

## Done =
Endpoint works on the live demo, guardrails tested, eval numbers in `docs/copilot-eval.md`, README section updated. Then tell me the numbers and I'll update `cv.md` and the resumes.
