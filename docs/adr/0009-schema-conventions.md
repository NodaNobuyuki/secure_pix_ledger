# ADR-0009: Schema conventions: checked text enums, named constraints, fixed system ids

- **Status:** Accepted
- **Date:** 2026-10-15

## Context

The Flyway schema is the last line of defense for the ledger rules in
[ADR-0006](0006-double-entry-ledger-and-money.md); Hibernate only validates it
(`ddl-auto=validate`). A few conventions had to be fixed before writing `V1`: how to store
enumerated values, how code finds the `SYSTEM_FUNDING` account, and how failures are identified.

## Decision

- **Enumerated values** (`type`, `status`, `rejection_reason`) are `VARCHAR` columns with a
  `CHECK (... IN (...))`, mapped with `@Enumerated(EnumType.STRING)`.
- **Every constraint has an explicit name** (`<table>_<rule>`), so a violation names the rule that
  failed. The application relies on this to recognize `transfers_idempotency_key_unique` when two
  requests with the same key race ([ADR-0008](0008-idempotency-keys.md)).
- **System accounts have fixed, well-known UUIDs.** `SYSTEM_FUNDING` is
  `00000000-0000-0000-0000-000000000001`, inserted by `V1` and mirrored by
  `SystemAccounts.FUNDING_ID`. A schema test checks that both agree.
- **`ledger_entries` is append-only by trigger**: `UPDATE`, `DELETE` and `TRUNCATE` raise an error.

## Alternatives considered

- **Native PostgreSQL `ENUM` types:** adding a value needs `ALTER TYPE` (with transaction
  restrictions on older versions), removing one is not supported, and JPA needs extra mapping. A
  `CHECK` constraint is replaced in an ordinary migration.
- **A unique `code` column on `accounts` (`'SYSTEM_FUNDING'`):** more readable in SQL, but it adds a
  column used by one row and a lookup on every deposit. The fixed id is just as stable.
- **Relying on default constraint names:** they are generated (`transfers_idempotency_key_key`) and
  can change if a constraint is redefined, which would silently break error mapping.

## Consequences

- Adding an enum value means a new migration that replaces the `CHECK`; forgetting it fails fast
  with a constraint violation instead of storing an unknown value.
- Triggers do not bind the table owner or a superuser, who can disable them. Today the application
  connects as the owner. Phase 2 should run the app with a least-privilege role (no DDL, no
  `TRUNCATE`) so the append-only rule holds even if the app is compromised. **Done:**
  [ADR-0012](0012-least-privilege-database-role.md).
- Fixed ids are predictable, which is fine for internal accounts because they are never exposed
  through the public API.
