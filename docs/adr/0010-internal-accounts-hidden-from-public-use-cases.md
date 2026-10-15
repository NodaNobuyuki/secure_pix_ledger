# ADR-0010: Internal accounts are invisible to public use cases

- **Status:** Accepted
- **Date:** 2026-10-15

## Context

`SYSTEM_FUNDING` is a `SYSTEM` account that may go negative, because it is where money is issued
([ADR-0006](0006-double-entry-ledger-and-money.md)). The domain allows debiting it, so that the
internal deposit use case can work. If the public transfer use case accepted any account id, a
client that learns the funding id (it is fixed, [ADR-0009](0009-schema-conventions.md)) could send
`source = SYSTEM_FUNDING` and mint money. Phase 1 has no authentication yet, but this is a
different problem from ownership: it breaks the money supply, not one customer's privacy.

## Decision

- Public use cases (`TransferService`, `AccountService`) treat `SYSTEM` accounts **exactly like
  accounts that do not exist**: `AccountNotFoundException`, later `404`. Transfers that touch an
  internal account (deposits) are not returned by `TransferService.find` either.
- Issuing money is a separate class, `DepositService`, which runs the same transfer code with
  internal accounts allowed. No controller depends on it; only seeds, tests and the `dev` profile
  do. Keeping it out of `TransferService` means a controller cannot call it by accident.
- Like other not-found errors, these requests are not stored and do not consume the idempotency
  key ([ADR-0008](0008-idempotency-keys.md)).

## Alternatives considered

- **Reject explicitly (`422`, "system accounts cannot be used"):** clearer when debugging, but it
  confirms to anyone probing ids that the id is an internal account.
- **Allow it in Phase 1 and rely on Phase 2 ownership checks:** leaves money issuance open in the
  meantime, and the ledger invariants would not catch it, since the entries still sum to zero.
- **A `deposit` flag on the public transfer method:** one switch away from being exposed.

## Consequences

- The check runs after the accounts are locked, so a public request naming `SYSTEM_FUNDING` briefly
  locks it before failing. Acceptable for now; if the funding account becomes a contention point,
  reject known internal ids before locking.
- Customers cannot yet see deposits into their accounts through `GET /transfers/{id}`. A statement
  endpoint in a later phase should show them without exposing the funding account's id.
