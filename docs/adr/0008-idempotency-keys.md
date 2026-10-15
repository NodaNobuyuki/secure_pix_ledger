# ADR-0008: Idempotency keys for transfer creation

- **Status:** Accepted
- **Date:** 2026-10-14

## Context

Networks fail after the server commits but before the client sees the response. A client that
retries `POST /transfers` must not move money twice, and two copies of the same request racing
in parallel (double click, aggressive retry policy) must produce exactly one transfer.

## Decision

**Contract**
- `POST /transfers` requires an `Idempotency-Key` header in UUID format. If it is missing or
  malformed, the response is `400`.
- The server stores the key with a `request_hash`: SHA-256 of a **canonical** form of the
  validated request, `v1|<source>|<destination>|<amountMinor>|<currency>`. The hash is computed
  from parsed fields, not raw bytes, so whitespace and JSON key order do not matter. The `v1`
  prefix lets the format evolve.

**Outcomes**

| Situation | Response |
|---|---|
| New key | Process normally: `201` (completed) or `422` (rejected, see below) |
| Known key, same hash | Replay the original outcome (same status and body) with `Idempotent-Replayed: true` |
| Known key, different hash | `422`, problem type `idempotency-key-reused`; nothing is executed |

**Rejections consume the key.** A business-rule rejection after both accounts are found
(insufficient funds, inactive account) is stored as a transfer with status `REJECTED` and a
reason, with no entries and no balance change, and committed. A retry with the same key replays the
same `422`, so a key always maps to one outcome. To try again, the client sends a new key. The
stored rejections also feed the audit trail (Phase 3) and the "repeated failures" fraud rule
(Phase 4).
Requests that never reach that point are **not** stored and do not consume the key: malformed
input, same source and destination, unknown accounts.

**Races.** `UNIQUE (idempotency_key)` on `transfers` is the real guard. The application checks for
an existing key first (fast path), but two requests can both pass that check. When that happens,
PostgreSQL blocks the second `INSERT` on the unique index until the first transaction finishes.
It then raises a unique violation, the loser's transaction rolls back, and the loser reads the
winner's row **in a new transaction** and replays it (or returns `422` if the hash differs).

## Alternatives considered

- **No idempotency, tell clients not to retry:** unsafe; retries happen anyway.
- **Natural deduplication (same amount and accounts within N seconds):** blocks legitimate
  repeated payments and is still racy.
- **Separate idempotency table storing raw responses:** more general (any endpoint), but
  duplicates data we already have in `transfers`. We can extract it if more endpoints need
  idempotency.
- **`409` for a concurrent in-flight duplicate:** forces the client to retry. Blocking on the
  unique index gives the client the final answer directly.

## Consequences

- One transfer row per key, enforced by the database, not just by application code.
- Keys are unique globally in Phase 1. Phase 2 scopes them per client:
  `UNIQUE (client_id, idempotency_key)`, so one client cannot probe or collide with another's keys.
- Keys never expire for now. A retention policy (for example, 24 h of replay) can come later; it
  does not change correctness.
- Tests must cover sequential replay, parallel replay (one transfer, two entries) and
  hash mismatch → `422`.
