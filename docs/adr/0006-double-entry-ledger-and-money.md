# ADR-0006: Double-entry ledger, materialized balances and money as minor units

- **Status:** Accepted
- **Date:** 2026-10-14

## Context

The system's first job is to never create or lose money: not through rounding, not through
concurrent requests, not through a bug in one code path. We need a model where errors are
detectable after the fact, and where the database refuses obviously invalid states even if the
application is wrong.

## Decision

**Money is an integer count of minor units.** `Money` is a value object holding a `long` amount
in centavos plus a currency (BRL only for now). Arithmetic uses `Math.addExact`/`subtractExact`, so
overflow throws instead of wrapping. Columns are `BIGINT`. No `double`, no `float`.

**Every movement is a double-entry transfer.** A completed transfer appends exactly two
`ledger_entries`: a negative entry (debit) on the source account and a positive entry (credit) on
the destination, with the same absolute amount. Therefore:

- the entries of every transfer sum to zero, and
- the sum of **all** entries in the system is zero.

**Entries are append-only.** A database trigger rejects `UPDATE` and `DELETE` on `ledger_entries`.
Corrections are new compensating transfers, never edits.

**Balances are materialized and verifiable.** `accounts.balance_minor` is updated in the same
transaction that inserts the entries, so reads are O(1). It is a cache of the ledger, not the
source of truth: `balance_minor == SUM(amount_minor)` of the account's entries must always hold,
and the test suites check it.

**The database enforces the critical rules**, as defense in depth:

- `CHECK (type = 'SYSTEM' OR balance_minor >= 0)`: a customer account cannot go negative.
- `CHECK (amount_minor > 0)` on transfers and `CHECK (amount_minor <> 0)` on entries.
- Foreign keys from entries to transfers and accounts.

**Money enters through a system account.** Since the global sum must be zero, balances cannot be
inserted directly. A `SYSTEM_FUNDING` account (type `SYSTEM`, allowed to go negative) is created by
a Flyway migration. Funding a customer is an ordinary transfer from `SYSTEM_FUNDING`, done by an
internal deposit use case. It has **no HTTP endpoint**; only seeds, tests and the `dev` profile
use it. `-balance(SYSTEM_FUNDING)` is the total money issued.

## Alternatives considered

- **`NUMERIC` + `BigDecimal`:** exact too, but easier to misuse (scale, `equals` versus
  `compareTo`, rounding modes), and slower. Integer centavos cover every BRL amount we need.
- **Balance computed only from entries:** a single source of truth, but every balance read and
  overdraft check becomes an aggregate over a growing table, and under locking it is harder to
  get right.
- **Single-entry (just a balance column):** the simplest option, but no history, and corruption
  cannot be detected.
- **Seeding balances directly:** convenient, but it breaks both invariants on day one.

## Consequences

- Invariants are mechanically testable: global sum is zero, and balance equals the sum of entries
  per account.
- Every write path must go through the transfer use case. Writing a balance from anywhere else is
  a bug, and the verification check catches it.
- Supporting multiple currencies later requires per-currency accounts and FX transfers. That is out
  of scope.
- Rejected transfers are stored without entries (see [ADR-0008](0008-idempotency-keys.md)), so
  "two entries per transfer" applies to `COMPLETED` transfers only.
