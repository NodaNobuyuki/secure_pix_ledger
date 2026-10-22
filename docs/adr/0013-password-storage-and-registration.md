# ADR-0013: Hash passwords with Argon2id; log in with usernames, not emails

- **Status:** Accepted
- **Date:** 2026-10-20

## Context

Phase 2 adds registration and login. If the users table ever leaks, the password hashes are what
stands between the attacker and the users' passwords, so they must be slow and expensive to crack
offline, including on GPUs. The brief allows Argon2 or bcrypt. It also asks for safe error
messages and synthetic data only, so we should collect as little personal data as possible.

## Decision

- **Argon2id**, using Spring Security's `Argon2PasswordEncoder` (its implementation comes from
  BouncyCastle), with the OWASP minimum parameters: 19 MiB of memory, 2 iterations,
  parallelism 1, a 16-byte salt and a 32-byte hash.
- It is wrapped in a `DelegatingPasswordEncoder`, so every stored hash names its algorithm and
  parameters (`{argon2}$argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>`). Stronger settings can be
  rolled out later by rehashing at the next login, without invalidating existing hashes.
- **Hashing never runs inside a database transaction.** A slow hash inside a transaction would
  hold a pooled connection, so a flood of sign-ups or logins could starve transfers of
  connections. `UserRepository` methods are atomic on their own.
- **Passwords:**
  - **Length:** 12 to 128 characters, counted as Unicode code points.
  - **NFKC normalization first**, so the same password typed with differently composed
    characters (`é` as one code point, or `e` plus an accent) gives the same hash.
  - **No composition rules and no expiry**, following NIST SP 800-63B.
  - The upper bound caps the hashing work for a single request.
  - **Never logged:** plaintext lives only in a `Password` type whose `toString()` hides it, and
    the request DTO hides it too.
- **Usernames, not emails:**
  - **Format:** 3 to 32 characters from `[a-z0-9._-]`. Input is lowercased (`Locale.ROOT`)
    before it is checked and stored, so a plain `UNIQUE` constraint makes them case-insensitive.
  - **ASCII only**, which rules out look-alike characters from other scripts (Cyrillic `а` for
    Latin `a`).
  - **No email:** no personal data and no verification flow.
- **Registration always creates a `CUSTOMER`.** The request has no role field, and strict JSON
  ([ADR-0011](0011-http-error-contract.md)) rejects one, which blocks mass assignment. Admins are
  never self-registered.
- **A taken username is a `409 username-taken`.**
- **Database guards:**
  - `CHECK`s for the username format and the role.
  - `UNIQUE (username)`.
  - A `CHECK` that `password_hash` starts with an `{algorithm}` prefix, so a bare string (a
    plaintext password, by mistake) is refused.
  - The application role gets `SELECT` and `INSERT` on `users` and no `UPDATE`
    ([ADR-0012](0012-least-privilege-database-role.md)), so SQL injection cannot promote anyone
    to `ADMIN`.

## Alternatives considered

- **bcrypt:** needs no extra dependency, but it silently truncates input at 72 bytes and is not
  memory-hard, so GPUs crack it more cheaply.
- **PBKDF2 or scrypt:** PBKDF2 is not memory-hard. scrypt is, but OWASP ranks Argon2id first.
- **Email as the login:** the common choice in real products, but it brings personal data, a
  verification flow and the same "already registered" disclosure.
- **Never reveal a taken username (always answer `201`):** this only works with an out-of-band
  channel such as email to tell the real owner. Without one, users could not tell whether
  sign-up worked.
- **A pepper (a server-side secret mixed into every hash):** helps if only the database leaks,
  but needs key management and rotation. It can be added later through the delegating encoder.
- **Checking passwords against breach lists (for example HIBP k-anonymity):** NIST recommends it,
  but it needs network calls or a large bundled list. Recorded as a limitation.

## Consequences

- Each hash costs tens of milliseconds and 19 MiB, by design. Registration and login are
  expensive endpoints, so per-IP rate limiting (a later Phase 2 step) has to bound them. Every
  test that creates a user pays this cost too.
- Login must build the same `Password` type (NFKC), or users with non-ASCII passwords could not
  log in.
- BouncyCastle is a new dependency, pinned in `pom.xml` because Boot does not manage it.
  Dependency-Check scans it in CI.
- Registration reveals whether a username exists. This is accepted, mitigated by rate limiting,
  and recorded in the threat model.
- There is no breach-list check and no pepper yet.
