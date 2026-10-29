# ADR-0011: HTTP error contract: RFC 9457 problems, strict JSON, no echoed input

- **Status:** Accepted
- **Date:** 2026-10-15

## Context

The API is the boundary where untrusted input meets money. Clients need machine-readable errors to
decide whether to retry, fix the request or give up. The brief asks for input validation and safe
error messages (no stack traces, no enumeration). Framework defaults fall short in three places:
Jackson silently coerces numbers (`1.5` becomes `1`, `"100"` becomes `100`) and ignores unknown
fields; Spring's default error details repeat the rejected value and name Java types.

## Decision

**Every error is an RFC 9457 problem** (`application/problem+json`), produced by one
`@RestControllerAdvice`.

| Situation | Status | `type` |
|---|---|---|
| Malformed JSON, invalid field, missing or non-UUID `Idempotency-Key` | `400` | `about:blank` (+ `errors` list for fields) |
| Unknown account, or an internal one ([ADR-0010](0010-internal-accounts-hidden-from-public-use-cases.md)) | `404` | `account-not-found` |
| Unknown transfer | `404` | `about:blank` |
| Business rejection, stored as `REJECTED` ([ADR-0008](0008-idempotency-keys.md)) | `422` | `transfer-rejected` (+ `reason`, `transferId`) |
| Key reused for a different request | `422` | `idempotency-key-reused` |
| Login failed, whatever the reason ([ADR-0014](0014-jwt-access-tokens.md)) | `401` + `WWW-Authenticate: Bearer` | `invalid-credentials` |
| Missing access token ([ADR-0014](0014-jwt-access-tokens.md)) | `401` + `WWW-Authenticate: Bearer` | `about:blank`, fixed detail |
| Rejected access token: expired, bad signature, wrong claims | `401` + `WWW-Authenticate: Bearer error="invalid_token"` | `about:blank`, the same fixed detail |
| Authenticated, but not allowed | `403` | `about:blank`, fixed detail |
| Username already registered ([ADR-0013](0013-password-storage-and-registration.md)) | `409` | `username-taken` |
| Semantically invalid transfer (same account, currency mismatch) | `422` | `invalid-transfer` |
| Lock wait timed out ([ADR-0007](0007-pessimistic-locking.md)) | `503` + `Retry-After: 1` | `account-busy` |
| Anything else | `500` | `about:blank`, fixed detail, logged server-side |

- **Type URIs are absolute and resolvable**: they point to sections of
  [`docs/problems.md`](../problems.md) in this repository, so a client (or a person) can look up
  what each one means. Clients must match on the full URI, not on `title` or `detail`.
- **Details never echo input or internals.** Field errors list the field and the constraint
  message, not the rejected value. Type-mismatch details name the parameter only.
- **Strict JSON** (`application.properties`): unknown properties are rejected (no mass assignment),
  floats are not accepted as integers, and strings are not coerced to numbers. Each setting has a
  test that fails without it.
- `Location` headers are **relative** (`/transfers/{id}`), so they are never built from the
  client-controlled `Host` header.

## Alternatives considered

- **Custom error envelope (`{"error": {...}}`):** one more format for clients to learn; RFC 9457 is
  what Spring produces natively.
- **`409 Conflict` for a reused key:** also common, but `422` matches ADR-0008 and keeps all
  "this request cannot be processed as sent" outcomes in one class.
- **Unregistered URNs or a made-up domain for `type`:** not resolvable, or resolvable by someone
  else. A repository we own is both stable and documented.
- **Lenient JSON (framework default):** a truncated amount moves the wrong amount of money without
  any error.

## Consequences

- Renaming the repository or the docs file breaks the type URIs; they are part of the API
  contract and change only with a versioned migration note.
- New error cases must add a row here and a section in `docs/problems.md`.
- Phase 2 adds `401`/`403`/`429`, which follow the same rules (no user enumeration).
