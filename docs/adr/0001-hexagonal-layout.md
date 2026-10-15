# ADR-0001: Use a lightweight hexagonal package layout

- **Status:** Accepted
- **Date:** 2026-10-12

## Context

The core of this project is money movement: a double-entry ledger, transfer rules and invariants
(no overdraft, entries always sum to zero). Those rules must be testable quickly and in isolation,
and must not change when we swap a web framework detail, a persistence mapping or a security filter.

A layer-per-technology layout (`controller/`, `service/`, `repository/`) tends to let JPA entities
and Spring annotations leak into business rules, which makes them hard to unit test and easy to
break by accident.

## Decision

Organize `dev.carlosnobuyuki.bank` into four packages with a one-way dependency rule:

| Package | Contains | May depend on |
|---|---|---|
| `domain` | Value objects and rules (`Money`, `Account`, `Transfer`, `LedgerEntry`, domain exceptions). Plain Java, no Spring/JPA. | nothing |
| `application` | Use cases (e.g. `TransferService`) and **ports**: interfaces for what the use cases need (repositories, clock, audit). | `domain` |
| `infrastructure` | Adapters that implement the ports: JPA entities and repositories, configuration. | `application`, `domain` |
| `api` | REST controllers, request/response DTOs, error handling (RFC 9457 `ProblemDetail`). | `application`, `domain` |

"Lightweight" means one Maven module and package conventions, not a multi-module build or a
framework. Transactions are declared on use cases in `application`; that is the one Spring
annotation allowed there, because the transaction boundary is a use-case concern.

## Alternatives considered

- **Layer-per-technology:** familiar and less code, but couples rules to the framework.
- **Multi-module Maven (one module per layer):** the compiler enforces the rule, but adds build
  complexity that is not worth it at this size.

## Consequences

- Domain rules get fast unit tests with no Spring context and no database.
- Mapping between JPA entities and domain objects is extra code we accept.
- The dependency rule is a convention for now. If it starts drifting, add an ArchUnit test to
  enforce it in `./mvnw verify`.
