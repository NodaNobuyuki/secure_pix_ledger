# ADR-0012: Run the application as a least-privilege database role

- **Status:** Accepted
- **Date:** 2026-10-20

## Context

The schema's constraints and the append-only trigger on `ledger_entries` are the last line of
defense for the ledger ([ADR-0006](0006-double-entry-ledger-and-money.md)). But they only bind
roles that cannot remove them. As [ADR-0009](0009-schema-conventions.md) noted, in Phase 1 the
application connected as the image's superuser, which also owns every table. Anyone who could
run SQL as the application could disable the trigger and rewrite history, drop the overdraft
constraint, or turn a customer account into a `SYSTEM` account that may go negative.

There are two attackers to consider:

- **SQL injection:** runs SQL with the application's database role.
- **Code execution in the application:** can also read its environment and files, including
  every credential the container holds.

## Decision

- **Two roles.**
  - **The owner** (`POSTGRES_USER` in compose) owns the schema and is used only to run
    migrations.
  - **The application role** (`APP_DB_USER`, `bank_app` by default) owns nothing. It is
    `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION`, and
    `ops/postgres/init/01-create-app-role.sh` creates it when the database volume is first
    initialized.
- **Grants are explicit, per table and per column, in migrations.** The role name comes in
  through the Flyway placeholder `${app_role}`.

  | Table | Application role may |
  |---|---|
  | `accounts` | `SELECT`, `INSERT`, `UPDATE (balance_minor, status)` |
  | `transfers` | `SELECT`, `INSERT` |
  | `ledger_entries` | `SELECT`, `INSERT` |
  | `flyway_schema_history` | nothing |

  There is no `DELETE` or `TRUNCATE` anywhere. Because the role owns nothing, it cannot run DDL,
  drop constraints or disable triggers. PostgreSQL 15+ already refuses it `CREATE` on the
  `public` schema.
- **Migrations run in their own one-shot container.** In compose, a `migrate` service holds the
  owner credentials, applies the migrations and exits. It uses the official Flyway image, at the
  same version Spring Boot manages. The `app` service starts after it with Flyway disabled and
  only the application role's credentials, so even code execution in the application yields no
  owner credentials.
- **Tests use the same split.** Flyway migrates as the container's owner, and the whole
  application context connects as the application role, which is created by the same init
  script. A missing grant fails the suite instead of passing unnoticed.
  - `LeastPrivilegeTests` asserts that destructive statements fail with SQLSTATE `42501`
    (insufficient privilege).
  - The trigger tests run as the owner, so the trigger is proven on its own, separately from the
    grants.

## Alternatives considered

- **Keep connecting as the owner:** leaves the triggers and constraints removable by the
  application they are meant to guard (ADR-0009).
- **Run Flyway inside the application, with owner credentials in its environment:** the simplest
  option, and it stops SQL injection. But code execution in the application reads the owner
  credentials and gets everything back.
- **Create the role in a Flyway migration:** the migration user would need `CREATEROLE`, and the
  password would have to pass through migration placeholders. Provisioning roles and secrets is
  an operator task, not a schema change.
- **`ALTER DEFAULT PRIVILEGES` for future tables:** convenient, but it grants access to every new
  table by default, the opposite of least privilege.
- **Row-level security per user:** finer-grained, but it needs the caller's identity on every
  pooled connection. Phase 2 enforces ownership in the application layer; this can be revisited
  as defense in depth.

## Consequences

- Every migration that adds a table or column the application writes must grant it. Forgetting
  fails the tests with `permission denied`.
- Hibernate writes every updatable column of an entity on update, so each such column needs an
  `UPDATE` grant. Columns the application must never change are mapped `updatable = false`.
  `FOR UPDATE` row locks also need `UPDATE` on at least one column, which this grant covers.
- The Flyway image tag in `docker-compose.yml` must follow Boot's managed `flyway.version`
  (12.4.0 with Boot 4.1.1). Bump them together.
- The init script runs only on a fresh volume, so existing local databases need
  `docker compose down -v` once.
- In compose the owner is still the image's superuser. A production setup would migrate with a
  non-superuser owner role as well; that is out of scope for the local stack.
