Leer en español: [README.md](README.md)

# Financial Transactions — Transaction Execution API

REST API to execute and query financial transactions (CREDIT/DEBIT) against an external
provider, with real idempotency, multi-layer resilience (timeouts, retries, circuit
breaker), and PostgreSQL persistence.

## Architecture

Hybrid hexagonal architecture across 5 packages under
`com.financial.transactions.challenge`:

```
                        ┌──────────────┐
                        │  controller  │  HTTP ↔ domain (DTOs, versioning, error handling)
                        └──────┬───────┘
                               │ depends on
                        ┌──────▼───────┐
                        │   service    │  use cases (ExecuteTransactionService,
                        │  service/port│  QueryTransactionService) + port interfaces
                        └──┬────────┬──┘
                 implements│        │implements
          ┌────────────────▼┐    ┌──▼─────────────┐
          │   repository     │    │    provider     │
          │ (JdbcClient +    │    │ (RestClient +   │
          │  PostgreSQL)     │    │  Resilience4j)  │
          └──────────────────┘    └─────────────────┘
                        ┌──────────────┐
                        │    domain    │  Transaction, Money, business rules,
                        │              │  exceptions — no framework dependencies
                        └──────────────┘
```

**Dependency rule**: `domain` depends on nothing. `service` depends only on `domain`
and its own interfaces (`service/port/TransactionRepository`,
`service/port/TransactionProvider`). `repository` and `provider` are adapters that
implement those interfaces — `service` never knows about JDBC, Postgres, RestClient, or
Resilience4j directly. `controller` depends on `service`, never the other way around.

## Design decisions

- **Tomcat + a dedicated `ExecutorService`, not a reactive stack (WebFlux/R2DBC)**: the
  system's I/O-bound profile (one outbound HTTP call per transaction) would in theory
  favor a reactive stack for throughput with few threads. The more common pattern in
  fintech production and the one most requested in interviews was prioritized instead,
  with a considerably simpler learning curve and debugging story (linear stack traces,
  no reactive operators to trace through).
- **`JdbcClient` with explicit SQL, not JPA/Hibernate**: full control over the queries
  (including the `upsert` with `ON CONFLICT` that idempotency needs) without an ORM's
  layer of indirection or lazy-loading surprises.
- **PostgreSQL + Flyway**: versioned, immutable migrations (`V001`...`V003`) as the
  source of truth for the schema, reproducible in any environment.
- **Real idempotency, not just deduplication**: the `Idempotency-Key` header is
  required. `EXECUTED` and `REJECTED` are terminal states — the provider already gave a
  definitive business response, so a client retry returns the frozen transaction
  without touching the provider. `FAILED` (there was never a real response from the
  provider) is **not** terminal: a retry with the same key genuinely calls the provider
  again, updating the same record via `upsert` (`ON CONFLICT (id)`) — same `id`, same
  `created_at`, status and provider data updated.
- **Resilience across 4 layers**, all externalized in `application.yml` (nothing
  hardcoded):
  1. **Socket** — the `RestClient`'s connect/read timeout (`app.provider.connect-timeout`,
     `app.provider.read-timeout`): the expected "slow provider" case.
  2. **`Future.get(timeout)`** on a dedicated `ExecutorService`
     (`app.provider-executor.future-timeout`): a safety net for hangs that don't
     depend on the socket (DNS, pool contention).
  3. **Retry with exponential backoff** (Resilience4j
     `retry.instances.transactionProvider`): retries timeouts/communication failures,
     never business rejections.
  4. **Circuit Breaker** (Resilience4j
     `circuitbreaker.instances.transactionProvider`): stops hammering a downed
     provider, with automatic half-open transitions.
  A provider rejection (`ProviderRejectedException`, e.g. insufficient funds) is
  explicitly excluded from both retry and the circuit breaker — it's a valid business
  outcome, not an infrastructure failure.
- **Why there's no separate dead-letter table**: `status=FAILED` plus the
  `failure_reason` column on `transactions` already serves that purpose. This is a
  synchronous API with no queue or automatic reprocessing — a dead-letter table would
  add infrastructure with no real consumer to process it within this scope.
- **Why there's no PostgreSQL partitioning or read/write pool split**: documented as
  future scalability steps, dropped from scope pragmatically given the challenge's
  time constraints — there's no evidence current volume would justify them.
- **API versioning via the `X-API-Version` header** (Spring Framework 7's native
  support, not a URL prefix): with a default version (`"1"`) configured, a client that
  doesn't send the header still works — versioning is a safety net for the future, not
  an entry barrier.

## Running the project

**1. Start the infrastructure** (PostgreSQL + mock provider via WireMock):

```bash
docker-compose up -d
```

This exposes PostgreSQL on `localhost:5433` and the mock provider on `localhost:9090`
(WireMock stubs live in `wiremock/mappings/` and load automatically).

**2. Start the application**:

```bash
./gradlew bootRun
```

The API is available at `http://localhost:8080`.

**`local` profile** (for manually demoing resilience — see the Postman section below):
shrinks the circuit breaker's thresholds so it can be opened in minutes instead of
needing 10+ real failed calls, and exposes actuator endpoints to inspect its state:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

or, equivalently:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

## Running the tests

```bash
./gradlew test
```

Four test layers, each scoped to be as fast and reliable as its concern allows:

| Layer | Tool | What it covers |
|---|---|---|
| `service/` | JUnit 5 + pure Mockito | Business rules, idempotency, mapping provider results — no Spring, milliseconds per test |
| `repository/` | Testcontainers (real Postgres) | Real SQL, Flyway migrations, the idempotency `upsert`, filters and pagination |
| `provider/` | WireMock | Timeouts, retry with backoff, circuit breaker — against a real HTTP server, not Java mocks |
| `controller/` | MockMvc + Testcontainers + WireMock | Full end-to-end stack: HTTP → controller → service → real repository → real provider (mocked at the network level) |

> **Note**: the `provider/` and `controller/` tests spin up their own WireMock
> instance on port `9090` (independent of Testcontainers). If docker-compose's
> `mock-provider` is still running on that same port when you run `./gradlew test`,
> there will be a port conflict. Stop it with `docker-compose stop mock-provider`
> (or `docker-compose down`) before running the automated tests — not needed for
> `./gradlew bootRun`, only for the tests.

## Testing manually with Postman

Import `postman/financial-transactions.postman_collection.json`. The collection
assumes the app is running on `http://localhost:8080` and WireMock on
`http://localhost:9090` (collection variables `baseUrl`/`wiremockUrl`, editable).

It's organized into numbered folders, meant to be run in order:

1. **Happy Path** — a valid CREDIT, approved by the provider.
2. **Business Rule Validations** — invalid amounts, the DEBIT limit, unsupported
   currency, a missing header.
3. **Provider Rejection** — the provider rejects the transaction (a business outcome,
   not an HTTP error).
4. **Idempotency** — successful reuse, and a conflict with different data.
5. **Timeout & Retry Demo** — the provider hangs; you can see Resilience4j's internal
   retry in WireMock's request journal; and retrying a FAILED transaction.
6. **Circuit Breaker Demo** (requires the `local` profile active) — consecutive failed
   calls open the circuit; confirms subsequent calls fail instantly without touching
   the network; inspecting `/actuator/health` and `/actuator/circuitbreakerevents`.
7. **Query & Pagination** — status/type filters and pagination.

**"Magic switches"** to trigger each mock provider scenario (see
`wiremock/mappings/`):

| Value in the request | Effect |
|---|---|
| `amount: 9999999` | The provider rejects with `INSUFFICIENT_FUNDS` (402) |
| `accountId: "acc-timeout-test"` | The provider hangs for 8s (triggers timeout + retry) |
| `accountId: "acc-cb-demo"` | A 3-step scenario: two failures in a row, then recovers — to demonstrate the circuit breaker |
| Any other value | The provider approves automatically |

Every request in the collection includes a description explaining what it demonstrates
and what response to expect, so it can be used without this README open side by side.

## Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/transactions` | Executes a transaction (requires the `Idempotency-Key` header) |
| `GET` | `/transactions` | Queries transactions (query params: `accountId`, `status`, `type`, `page`, `limit`) |

Both require the `X-API-Version` header (optional; defaults to `"1"` if omitted).

## OpenAPI / Swagger documentation

The API documentation is generated automatically from the real controllers and DTOs
(`springdoc-openapi`), in English. With the app running (`./gradlew bootRun`):

| Resource | URL |
|---|---|
| Swagger UI (interactive) | http://localhost:8080/swagger-ui.html |
| JSON spec | http://localhost:8080/v3/api-docs |
| YAML spec | http://localhost:8080/v3/api-docs.yaml |

The spec is also checked into the repo as a static file at [`docs/openapi.yaml`](docs/openapi.yaml)
and [`docs/openapi.json`](docs/openapi.json) — useful for importing into other tools
(Postman, Redoc, etc.) without the app running. To regenerate it after a change to
the endpoints/DTOs:

```bash
./gradlew bootRun &
sleep 5
curl -s http://localhost:8080/v3/api-docs.yaml -o docs/openapi.yaml
curl -s http://localhost:8080/v3/api-docs | python3 -m json.tool > docs/openapi.json
kill %1
```

> The Gradle plugin `org.springdoc.openapi-gradle-plugin` was not used because its
> latest release (1.9.0) predates Spring Boot 4.1/Spring Framework 7 and adds the
> complexity of booting the full app (with a real Postgres instance) inside the
> build. The manual export above is simpler and more reliable, and requires the same
> prerequisite anyway (the app running).

## Use of Artificial Intelligence

Claude (Anthropic) was used throughout this challenge's development for: generating
repetitive boilerplate (DTOs, migrations, configuration), resolving architecture
questions and questions about specific Spring Boot 4 / Spring Framework 7 APIs (several
verified against the actual module source code, given how recent the framework is),
debugging integration failures (Testcontainers/WireMock timing, the interaction between
Resilience4j and the HTTP connection pool), and generating the test suite following a
BDD approach (given/when/then) from cases designed beforehand by the author, not
decided by the AI: for each layer, the behaviors to cover, the boundaries/edge cases to
test (e.g. the exact DEBIT limit, the minimum amount, idempotency status transitions),
and the type of test each one required (unit test with Mockito, integration test with
Testcontainers, integration test with WireMock, or full end-to-end integration) were all
defined in advance — the AI implemented those already-defined cases, it did not
unilaterally decide which ones to write.
