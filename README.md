# secure-pix-ledger

[![CI](https://github.com/NodaNobuyuki/secure_pix_ledger/actions/workflows/ci.yml/badge.svg)](https://github.com/NodaNobuyuki/secure_pix_ledger/actions/workflows/ci.yml)
[![CodeQL](https://github.com/NodaNobuyuki/secure_pix_ledger/actions/workflows/codeql.yml/badge.svg)](https://github.com/NodaNobuyuki/secure_pix_ledger/actions/workflows/codeql.yml)

A mini core-banking API with Pix-style instant transfers: a double-entry ledger that moves money
correctly under concurrency and retries, protected by strong authentication and authorization,
with a tamper-evident audit trail, fraud detection and observability.

> [!WARNING]
> **Synthetic data only.** This is a learning and portfolio project. It is not connected to the
> real Pix system, holds no real personal or financial data, and must not be used to process
> real money.

## Status

**Phase 1 (financial correctness) is complete.** Phase 2 (identity) is in progress: users can
register and log in, every account and transfer endpoint requires an access token, and each user
can see and move only their own money. Tokens cannot be revoked and nothing limits login attempts
yet, so run it only on your own machine. See the [roadmap](#roadmap) and
the [known limitations](#known-limitations).

## What the ledger guarantees

Each guarantee is enforced in the application and, where possible, again by the database, and
each one has a test that tries to break it.

| Guarantee | How | Proven by |
|---|---|---|
| Money is never created or lost | Double-entry ledger: a completed transfer writes a debit and a credit that sum to zero. New money comes only from an internal funding account, whose negative balance is the total issued. ([ADR-0006](docs/adr/0006-double-entry-ledger-and-money.md)) | `LedgerInvariantExtension`, after every integration test |
| No overdraft, even under concurrency | Both accounts are row-locked before the balance check; a `CHECK` constraint rejects a negative customer balance anyway | `ConcurrentTransfersTests`: 100 × R$5 out of R$100, exactly 20 succeed |
| No deadlocks | Both rows are locked in one query, in id order ([ADR-0007](docs/adr/0007-pessimistic-locking.md)) | `ConcurrentTransfersTests`: 200 crossed A↔B transfers |
| A transfer happens at most once per `Idempotency-Key` | Lookup, then a `UNIQUE` constraint on (user, key); the loser of a race replays the winner. Keys are per user, so nobody can replay or block someone else's ([ADR-0008](docs/adr/0008-idempotency-keys.md)) | `ParallelIdempotencyTests`: 50 identical requests at once, one transfer |
| Only the owner can move an account's money | Ownership is checked before any row lock, and someone else's account answers exactly like a missing one. A trigger rejects a transfer whose initiator does not own the source, whatever the code does ([ADR-0015](docs/adr/0015-resource-ownership.md)) | `TransferApiTests`, `TransferServiceTests`, `SchemaConstraintsTests` |
| The ledger is append-only | Triggers reject `UPDATE`, `DELETE` and `TRUNCATE` on ledger entries | `SchemaConstraintsTests` |
| Lock waits are bounded | `lock_timeout` of 3 s; the client gets `503` with `Retry-After` and retries with the same key | `TransferServiceTests`, `TransferApiTests` |
| Internal accounts are invisible | The funding account answers exactly like an account that does not exist ([ADR-0010](docs/adr/0010-internal-accounts-hidden-from-public-use-cases.md)) | `AccountApiTests`, `TransferApiTests` |

Amounts are integers in minor units (centavos): `2500` is R$25.00. There are no floating-point
amounts anywhere, including the JSON parser, which rejects `25.5`.

## How a transfer works

`POST /transfers` runs in one database transaction:

1. Validate the body and the `Idempotency-Key` header (a UUID). Malformed requests are `400`, and
   the same source and destination is `422`; neither is stored, so the key stays unused.
2. If the caller already used the key: same body, replay the stored response with
   `Idempotent-Replayed: true`; different body, `422`.
3. Check that the caller owns the source account, then lock both accounts
   (`SELECT … FOR NO KEY UPDATE`, ordered by id). An unknown account, or a source the caller does
   not own, is `404`, also without consuming the key. Checking first means a stranger cannot even
   make the owner's transfers wait for a lock.
4. Apply the business rules (funds, active accounts). A refusal is **stored** as `REJECTED` and
   returned as `422`, so a retry replays the same refusal instead of re-evaluating it.
5. Insert the transfer and its two ledger entries, update both balances, commit.
6. If a concurrent request with the same key committed first, the insert hits the `UNIQUE`
   constraint; this request rolls back and replays the winner's result.

## API

| Method | Path | Result |
|---|---|---|
| `POST` | `/accounts` | `201`: opens a BRL account with a zero balance, owned by the caller |
| `GET` | `/accounts` | `200`: the caller's accounts, oldest first |
| `GET` | `/accounts/{id}` | `200`: status and balance of one of the caller's accounts |
| `POST` | `/transfers` | `201` completed, or `422` rejected; requires an `Idempotency-Key` header. The source must be the caller's; the destination can be anyone's |
| `GET` | `/transfers/{id}` | `200`: a transfer the caller sent, or a completed one they received |
| `POST` | `/auth/register` | `201`: registers a customer; `409` if the username is taken |
| `POST` | `/auth/login` | `200`: a 10-minute access token; `401` for any wrong credentials |

Every endpoint except register, login and the docs needs `Authorization: Bearer <accessToken>`;
without a valid token the answer is `401` ([ADR-0014](docs/adr/0014-jwt-access-tokens.md)).
Accounts and transfers are for customers: a token without the customer role gets `403`. The
caller is always the token's subject, never an id from the request, and someone else's account or
transfer gets the same `404` as one that does not exist, so ids cannot be probed
([ADR-0015](docs/adr/0015-resource-ownership.md)).

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problems
(`application/problem+json`), documented in [docs/problems.md](docs/problems.md). Unknown JSON
fields and type coercion are rejected, and error bodies never echo the input
([ADR-0011](docs/adr/0011-http-error-contract.md)). The OpenAPI spec is at `/v3/api-docs`, with
Swagger UI at `/swagger-ui`.

## Try it

The `dev` profile seeds two demo users, `alice` and `bob`, each owning one funded account with a
fixed id. Both get the password in `DEMO_USER_PASSWORD`; the dev profile refuses to start without
one. The variables below override `.env` for this shell; the stack never seeds unless you ask for
it. The commands need `curl`, `jq` and `openssl`.

```bash
cp .env.example .env                                  # then replace every "change-me" value
ops/scripts/generate-jwt-keys.sh                      # access token signing keys (once)
export DEMO_USER_PASSWORD=$(openssl rand -base64 18)  # synthetic; the demo users get it
SPRING_PROFILES_ACTIVE=dev docker compose up -d --build

login() { # login <username>: prints an Authorization header; the token lasts 10 minutes
  printf 'Authorization: Bearer %s' "$(jq -n --arg u "$1" --arg p "$DEMO_USER_PASSWORD" \
    '{username: $u, password: $p}' | curl -s http://localhost:8080/auth/login \
    -H 'Content-Type: application/json' -d @- | jq -r .accessToken)"
}
ALICE=$(login alice)
BOB=$(login bob)

A=d0000000-0000-4000-8000-000000000001                # alice's, seeded with R$1,000.00
B=d0000000-0000-4000-8000-000000000002                # bob's, seeded with R$50.00
transfer() { # transfer <auth header> <idempotency-key> <from> <to> <amount in centavos>
  curl -si http://localhost:8080/transfers -H "$1" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $2" \
    -d "{\"sourceAccountId\":\"$3\",\"destinationAccountId\":\"$4\",\"amountMinor\":$5,\"currency\":\"BRL\"}"
}
```

On a fresh database:

| Command | Response |
|---|---|
| `K1=$(uuidgen); transfer "$ALICE" $K1 $A $B 2500` | `201`, `Location: /transfers/{id}`, status `COMPLETED` |
| `transfer "$ALICE" $K1 $A $B 2500` (a client retry) | `201` with `Idempotent-Replayed: true` and the same transfer; no money moves |
| `transfer "$ALICE" $K1 $A $B 9900` | `422` `idempotency-key-reused` |
| `K2=$(uuidgen); transfer "$BOB" $K2 $B $A 1000000` | `422` `transfer-rejected`, reason `INSUFFICIENT_FUNDS` |
| `transfer "$BOB" $K2 $B $A 1000000` | the same `422`, with `Idempotent-Replayed: true` |
| `transfer "$BOB" $K1 $B $A 100` | `201`, a new transfer: keys are per user, so alice's key neither replays nor blocks bob's |
| `curl -si -H "$BOB" http://localhost:8080/accounts/$A` | `404` `account-not-found`: alice's account looks exactly like one that does not exist |
| `transfer "$BOB" $(uuidgen) $A $B 2500` | the same `404`: bob cannot move alice's money, and the key stays unused |
| `curl -s -H "$ALICE" http://localhost:8080/accounts` | only alice's account, balance `97600`; bob's lists `$B` with `7400` |
| `curl -si -H "$ALICE" http://localhost:8080/accounts/00000000-0000-0000-0000-000000000001` | `404`, like any unknown account: this is the internal funding account |
| `curl -si http://localhost:8080/accounts/$A` | `401` with `WWW-Authenticate: Bearer`: no token |

Restarting the app does not fund the accounts again: each seed deposit has a fixed idempotency
key, so later runs replay it. The demo users keep the password they were created with, so in a
new shell set the same `DEMO_USER_PASSWORD` again, or start over with `docker compose down -v`.

## Running locally

Prerequisites: [mise](https://mise.jdx.dev/) (or any JDK 21) and Docker with Compose.

```bash
mise install                 # installs the JDK pinned in mise.toml
./mvnw verify                # build, unit + integration tests (needs Docker), Spotless, SpotBugs
```

Full stack (API, PostgreSQL, Prometheus, Grafana), without demo data:

```bash
cp .env.example .env         # then replace every "change-me" value
ops/scripts/generate-jwt-keys.sh   # access token signing keys, written to the git-ignored secrets/
docker compose up --build
```

| Service | URL |
|---|---|
| API | http://localhost:8080 |
| API docs (Swagger UI) | http://localhost:8080/swagger-ui |
| Prometheus | http://localhost:9090 (check `/targets`: `secure-pix-ledger` should be UP) |
| Grafana | http://localhost:3000 (credentials from `.env`; Prometheus datasource provisioned) |

All ports bind to `127.0.0.1`. The actuator runs on an internal management port (`8081`) that
is not published to the host; see [ADR-0005](docs/adr/0005-actuator-and-port-exposure.md).

Migrations run first, in a one-shot `migrate` container that holds the schema owner's
credentials. The API connects as `APP_DB_USER`, a role that can only read and write rows: no DDL,
no `DELETE`, no way to disable the ledger triggers
([ADR-0012](docs/adr/0012-least-privilege-database-role.md)). That role is created when the
database volume is first initialized, so a volume from before it existed needs one
`docker compose down -v`. The same goes for a volume holding accounts or transfers from before
accounts had owners: the ownership migrations refuse to guess an owner and ask for the reset.

Other useful commands:

```bash
./mvnw spotless:apply            # fix formatting
./mvnw dependency-check:check    # CVE scan (set NVD_API_KEY first; the first run is slow)
docker compose down              # stop the stack; add -v to also delete the data volumes
```

## Tests

Integration tests run against a real PostgreSQL 17 in Testcontainers, connecting as the same
least-privilege role as the deployed app, so a missing grant fails the build. Every class
annotated `@IntegrationTest` checks the ledger after each test: all entries sum to zero, and
every balance equals the sum of its account's entries.

| Suite | What it covers |
|---|---|
| `domain/*Test` | Money arithmetic and overflow, transfer and account rules (plain unit tests, no Spring) |
| `SchemaConstraintsTests` | The database alone rejects invalid states, bypassing the domain model, even for the schema owner |
| `LeastPrivilegeTests` | The app's database role cannot delete, truncate, alter tables, disable the ledger or ownership triggers, or change who owns an account or sent a transfer |
| `OwnershipMigrationTests` | The ownership migrations refuse to run over data that has no owner |
| `JpaRepositoriesTests` | Persistence adapters round-trip, and writes require the row lock |
| `TransferServiceTests` | Use cases: stored rejections, replay, key reuse, race replay, lock timeout, ownership (a stranger is refused without waiting for the owner's lock) |
| `RegistrationServiceTests` | Concurrent sign-ups with one username create exactly one user |
| `ConcurrentTransfersTests` | 500 transfers at once: a drained hot account, crossed pairs, a random mesh |
| `ParallelIdempotencyTests` | One key under contention: identical requests execute once, different ones get `422` |
| `*ApiTests` | HTTP contract: status codes, problem types, headers, strict JSON, tokens and roles, other users' resources |
| `DemoDataSeederTests` | Demo users and money exist only with the `dev` profile and a demo password, and money is issued only once |

CI runs the same `./mvnw verify` on every push and pull request, plus OWASP Dependency-Check and
CodeQL ([ADR-0003](docs/adr/0003-ci-supply-chain-hardening.md)).

## Project layout

```
src/main/java/dev/carlosnobuyuki/bank/
├── domain/          # money, accounts, transfers, ledger rules (plain Java)
├── application/     # use cases and ports
├── infrastructure/  # JPA adapters, configuration, dev-profile demo data
└── api/             # REST controllers, DTOs, error handling
src/main/resources/db/migration/   # Flyway migrations
docs/adr/                          # architecture decision records
docs/problems.md                   # error catalog
ops/                               # Prometheus and Grafana provisioning
```

Rationale in [ADR-0001](docs/adr/0001-hexagonal-layout.md).

## Stack

- Java 21 (Temurin), Spring Boot 4.1 ([why not 3.x](docs/adr/0002-spring-boot-4.md)), Maven wrapper
- PostgreSQL 17, Flyway, Spring Data JPA, springdoc-openapi
- Testcontainers for integration tests against a real PostgreSQL
- Micrometer + Prometheus + Grafana
- Quality and security gates: Spotless, SpotBugs + FindSecBugs, OWASP Dependency-Check, CodeQL

## Roadmap

Each phase has a Definition of Done and starts only after the previous one passes it.

1. ✅ **Financial correctness:** double-entry ledger, idempotency keys, pessimistic locking,
   concurrency and invariant test suites.
2. **Identity:** stateless Spring Security, Argon2id, short-lived JWTs, rotating refresh tokens
   with reuse detection, TOTP, IDOR protection, rate limiting, STRIDE threat model.
3. **Evidence:** hash-chained audit log with verification, red-team test suite attacking the
   API, red-team report.
4. **Intelligence:** rule-based fraud engine (score and allow/step-up/block), metrics and
   Grafana dashboards.

## Known limitations

These are deliberate at this point of Phase 2:

- **No logout or refresh yet.** An access token lasts 10 minutes and cannot be revoked; after
  that, log in again. Refresh tokens and logout are the next Phase 2 step.
- **No rate limiting or lockout yet.** Nothing slows down password guessing or floods of
  requests. A later Phase 2 step adds both.
- **No admin endpoints yet.** Admin tokens are refused on accounts and transfers (`403`); the
  support view they get instead comes later in Phase 2.
- **Swagger UI is public**, without a token. A later Phase 2 step limits it to the `dev` profile.
- **Money enters only through the `dev` seeder.** There is no public deposit endpoint, by design
  ([ADR-0010](docs/adr/0010-internal-accounts-hidden-from-public-use-cases.md)).
- **No statements.** Deposits into an account are not visible through `GET /transfers/{id}`, and
  there is no endpoint listing an account's history yet.
- **BRL only**, and accounts cannot be deactivated through the API.
- **No Grafana dashboards yet**, only the datasource; they arrive with the metrics in Phase 4.

## Architecture decisions

| ADR | Decision |
|---|---|
| [0001](docs/adr/0001-hexagonal-layout.md) | Lightweight hexagonal package layout |
| [0002](docs/adr/0002-spring-boot-4.md) | Spring Boot 4.1 instead of 3.x |
| [0003](docs/adr/0003-ci-supply-chain-hardening.md) | CI hardening and blocking security gates |
| [0004](docs/adr/0004-dependency-pins-and-overrides.md) | Pinned tool versions and the Tomcat CVE override |
| [0005](docs/adr/0005-actuator-and-port-exposure.md) | Actuator isolation and minimal port exposure |
| [0006](docs/adr/0006-double-entry-ledger-and-money.md) | Double-entry ledger, materialized balances, money as minor units |
| [0007](docs/adr/0007-pessimistic-locking.md) | Pessimistic row locks in deterministic order |
| [0008](docs/adr/0008-idempotency-keys.md) | Idempotency keys for transfer creation |
| [0009](docs/adr/0009-schema-conventions.md) | Schema conventions: checked text enums, named constraints, fixed system ids |
| [0010](docs/adr/0010-internal-accounts-hidden-from-public-use-cases.md) | Internal accounts are invisible to public use cases |
| [0011](docs/adr/0011-http-error-contract.md) | HTTP error contract: RFC 9457 problems, strict JSON, no echoed input |
| [0012](docs/adr/0012-least-privilege-database-role.md) | Run the application as a least-privilege database role |
| [0013](docs/adr/0013-password-storage-and-registration.md) | Hash passwords with Argon2id; log in with usernames, not emails |
| [0014](docs/adr/0014-jwt-access-tokens.md) | Short-lived RS256 JWT access tokens |
| [0015](docs/adr/0015-resource-ownership.md) | Accounts belong to users; someone else's resources look like missing ones |

New decisions start from the [template](docs/adr/0000-template.md).
