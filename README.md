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

Work in progress. Bootstrap (build, quality gates, container stack, CI) is done; the ledger
(Phase 1) is next. See the [roadmap](#roadmap).

## Stack

- Java 21 (Temurin), Spring Boot 4.1 ([why not 3.x](docs/adr/0002-spring-boot-4.md)), Maven wrapper
- PostgreSQL 17, Flyway, Spring Data JPA
- Testcontainers for integration tests against a real PostgreSQL
- Micrometer + Prometheus + Grafana
- Quality and security gates: Spotless, SpotBugs + FindSecBugs, OWASP Dependency-Check, CodeQL

## Running locally

Prerequisites: [mise](https://mise.jdx.dev/) (or any JDK 21) and Docker with Compose.

```bash
mise install                 # installs the JDK pinned in mise.toml
./mvnw verify                # build, unit + integration tests (needs Docker), Spotless, SpotBugs
```

Full stack (API, PostgreSQL, Prometheus, Grafana):

```bash
cp .env.example .env         # then replace every "change-me" value
docker compose up --build
```

| Service | URL |
|---|---|
| API | http://localhost:8080 |
| API docs (Swagger UI) | http://localhost:8080/swagger-ui (spec at `/v3/api-docs`) |
| Prometheus | http://localhost:9090 (check `/targets`: `secure-pix-ledger` should be UP) |
| Grafana | http://localhost:3000 (credentials from `.env`) |

All ports bind to `127.0.0.1`. The actuator runs on an internal management port (`8081`) that
is not published to the host; see [ADR-0005](docs/adr/0005-actuator-and-port-exposure.md).

Other useful commands:

```bash
./mvnw spotless:apply            # fix formatting
./mvnw dependency-check:check    # CVE scan (set NVD_API_KEY first; the first run is slow)
```

## Project layout

```
src/main/java/dev/carlosnobuyuki/bank/
├── domain/          # money, accounts, transfers, ledger rules (plain Java)
├── application/     # use cases and ports
├── infrastructure/  # JPA adapters, configuration
└── api/             # REST controllers, DTOs, error handling
src/main/resources/db/migration/   # Flyway migrations
docs/adr/                          # architecture decision records
ops/                               # Prometheus and Grafana provisioning
```

Rationale in [ADR-0001](docs/adr/0001-hexagonal-layout.md).

## Roadmap

Each phase has a Definition of Done and starts only after the previous one passes it.

1. **Financial correctness:** double-entry ledger, idempotency keys, pessimistic locking,
   concurrency and invariant test suites.
2. **Identity:** stateless Spring Security, Argon2id, short-lived JWTs, rotating refresh tokens
   with reuse detection, TOTP, IDOR protection, rate limiting, STRIDE threat model.
3. **Evidence:** hash-chained audit log with verification, red-team test suite attacking the
   API, red-team report.
4. **Intelligence:** rule-based fraud engine (score and allow/step-up/block), metrics and
   Grafana dashboards.

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

New decisions start from the [template](docs/adr/0000-template.md).
