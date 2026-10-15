# ADR-0007: Pessimistic row locks, acquired in a deterministic order

- **Status:** Accepted
- **Date:** 2026-10-14

## Context

Two transfers that read the same balance at the same time can both pass the overdraft check and
both debit, which is a classic lost update. The database `CHECK (balance_minor >= 0)`
([ADR-0006](0006-double-entry-ledger-and-money.md)) stops the balance going negative, but we want
the application to serialize correctly in the first place, not rely on a constraint failure.

Transfers touch **two** rows. Locking them in request order deadlocks: T1 locks A then waits for
B, while T2 locks B then waits for A.

## Decision

- Run each transfer in one transaction at PostgreSQL's default `READ COMMITTED` isolation.
- Before reading balances, lock both accounts with **one** query:
  `SELECT … FROM accounts WHERE id IN (:a, :b) ORDER BY id FOR UPDATE`
  (JPA `PESSIMISTIC_WRITE`). PostgreSQL acquires row locks as the sorted rows are returned, so
  every transaction locks in ascending `id` order, and the A→B versus B→A deadlock cannot form.
- Balance checks, entry inserts and balance updates happen only after both locks are held, on the
  freshly locked rows.
- Configure a **lock timeout** (PostgreSQL `lock_timeout`, a few seconds) so a stuck lock fails fast
  instead of exhausting the connection pool. A timeout is returned as a retryable error
  (`503` with `Retry-After`). Retrying is safe because the request carries an idempotency key
  ([ADR-0008](0008-idempotency-keys.md)).

## Alternatives considered

- **Optimistic locking (`@Version` + retry):** no blocking, but under contention on a hot account
  (payroll, a popular merchant, `SYSTEM_FUNDING` during seeding) most attempts fail and retry,
  which wastes work and adds tail latency. Retry logic also has to wrap the whole use case.
- **`SERIALIZABLE` isolation:** correct without explicit locks, but every write path must handle
  serialization failures with retries, and the conflicts are less predictable.
- **Advisory locks or a single-writer queue per account:** workable, but more moving parts than
  row locks for the same guarantee.
- **Atomic `UPDATE … SET balance = balance - x WHERE balance >= x`:** good for one row, but it does
  not give a consistent read of both accounts plus status checks in one step.

## Consequences

- Transfers on the same account run one at a time. Throughput per hot account is bounded by
  transaction duration, so transactions must stay short (no remote calls while holding locks;
  this matters for the fraud engine in Phase 4).
- The concurrency suite (hundreds of parallel transfers, including crossed A↔B pairs) must show no
  negative balances, no deadlocks and conserved totals. That suite is the proof that this ADR holds.
