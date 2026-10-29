# ADR-0015: Accounts belong to users; someone else's resources look like missing ones

- **Status:** Accepted
- **Date:** 2026-10-29

## Context

Since [ADR-0014](0014-jwt-access-tokens.md) every API call carries a token whose `sub` is the
caller's user id, but nothing links that id to data: any logged-in user can read any account and
move money out of it by changing an id in the URL or body (IDOR, OWASP API1:2023). Ids are random
UUIDs, which makes guessing hard, but ids leak (logs, screenshots, the payee of a transfer), so
unguessability is not access control. URL rules in the filter chain cannot fix this either: they
see `/accounts/{id}`, not who owns the row.

## Decision

**Ownership is data, enforced by the database**
- `accounts.owner_id` references `users`. `CHECK ((type = 'SYSTEM') = (owner_id IS NULL))`:
  every customer account has exactly one owner, internal accounts have none.
- `transfers.initiated_by` references `users`, `NULL` for internal deposits.
- Neither column can change: the application role has no `UPDATE` on them
  ([ADR-0012](0012-least-privilege-database-role.md)). That makes an ownership read safe to use
  without a lock.

**Checks live in the use cases** ([ADR-0001](0001-hexagonal-layout.md))
- Controllers take the caller's id from the token and pass it in; the application layer never sees
  Spring Security. The filter chain only adds a coarse rule: `/accounts/**` and `/transfers/**`
  need `ROLE_CUSTOMER`.
- The executor learns who moves the money from a sealed `Initiator`: `Customer(userId)` (customer
  accounts only, caller must own the source, stored as `initiated_by`) or `Internal` (deposits;
  any account, `NULL`). This replaces the `AccountScope` enum, so a scope can no longer disagree
  with an identity, and a `switch` over it must handle both cases.

**What each caller can do**

| Resource | Allowed | Otherwise |
|---|---|---|
| `GET /accounts` | Lists the caller's own accounts | n/a |
| `GET /accounts/{id}` | Owner | `404`, same body as an unknown id |
| `POST /transfers` | Caller owns the source; destination is any active customer account (Pix-style) | `404`, nothing stored, key not consumed |
| `GET /transfers/{id}`, completed | Owner of the source or the destination | `404` |
| `GET /transfers/{id}`, rejected | Owner of the source only | `404` |

- **Same 404 as a missing resource**, extending the [ADR-0010](0010-internal-accounts-hidden-from-public-use-cases.md)
  pattern: a `403` would confirm the id exists.
- **The source check runs before the rows are locked**, as a plain read. Checking after
  `FOR UPDATE` ([ADR-0007](0007-pessimistic-locking.md)) would let anyone who knows an account id
  hold its lock with transfers that are going to fail, delaying the owner's real transfers. A
  `SYSTEM` source has no owner, so it now also fails before locking. The scope checks on the
  locked rows stay as they are.
- **Rejected transfers are private to the payer.** A rejection never touched the destination, and
  it reveals the payer's state ("insufficient funds") to the payee.
- **Idempotency keys are scoped per initiator**: `UNIQUE NULLS NOT DISTINCT (initiated_by,
  idempotency_key)` ([ADR-0008](0008-idempotency-keys.md)). One user cannot replay another's
  transfer or block one of their keys; deposits (`NULL`) still share one key space.

## Alternatives considered

- **`403` for someone else's resource:** easier to debug, but an oracle for which ids exist.
- **Spring Security method security (`@PreAuthorize` with an ownership bean):** puts the rule in
  annotations on adapters, far from the transaction that reads the data, and pulls Spring Security
  into the application layer.
- **Row-level security in PostgreSQL:** a strong second layer, but it needs the user id in every
  connection (`SET LOCAL`), complicates pooling, and duplicates rules the use cases need anyway.
  Worth revisiting if more services share this database.
- **Ownership check after locking, as first planned:** one query fewer, but lets strangers take
  locks on victims' accounts.
- **A join table (several owners per account):** joint accounts are out of scope.

## Consequences

- IDOR tests cover each row of the table: another user's account, transfer source and transfer,
  plus the payee seeing a completed transfer and the same key used by two users.
- Anyone can still learn whether an id is an active customer account by sending a transfer to it
  (`404` vs `201`/`422`). That is inherent to paying any account; Pix exposes even the payee's
  name. Rate limiting (ADR-0017) bounds the probing.
- A transfer costs one more read (the source's owner) before locking.
- Dev databases must be reset once (`docker compose down -v`): existing customer accounts have no
  owner, and the migration stops with an explicit message instead of inventing one.
- The admin support view (Step 8) extends this ADR: admins read any customer resource through
  separate `/admin` use cases, never by relaxing these checks.
