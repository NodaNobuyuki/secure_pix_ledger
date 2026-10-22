# ADR-0014: Authenticate API calls with short-lived RS256 JWT access tokens

- **Status:** Proposed
- **Date:** 2026-10-22

## Context

Users can register ([ADR-0013](0013-password-storage-and-registration.md)), but no endpoint asks
who is calling: anyone who can reach the API can open accounts and move money between any two
accounts. The brief asks for short-lived JWTs, refresh tokens with rotation, MFA and safe errors.
This ADR covers logging in and checking the access token on every call. Refresh tokens
(ADR-0016), lockout (ADR-0017) and MFA (ADR-0018) build on it.

A JWT is checked without a database lookup, so its signature, its claims and the code that
checks them are the whole defense. The known ways that goes wrong are well documented
(RFC 8725): accepting `alg: none`, letting the token choose a weaker algorithm, confusing an
RSA public key with an HMAC secret, skipping `aud` or `iss`, and accepting one kind of token
where another is expected.

## Decision

- **We sign our own tokens; Spring Security's resource server verifies them.** There is no
  separate authorization server: `POST /auth/login` checks the password and issues the token,
  and `spring-boot-starter-oauth2-resource-server` (Nimbus) verifies it on every request.
- **RS256, fixed on both sides.**
  - The signer always uses RS256, and the decoder accepts only RS256. The token's `alg` header
    is checked against that, never trusted to choose, which rules out `alg: none` and
    RSA/HMAC confusion.
  - The header carries a `kid` (the key's RFC 7638 thumbprint) and `typ: at+jwt` (RFC 9068). The
    decoder requires that `typ`, so another kind of JWT we issue later cannot pass as an access
    token.
- **Keys:**
  - An RSA key pair is read from PEM files at `JWT_PRIVATE_KEY_PATH` and `JWT_PUBLIC_KEY_PATH`.
  - `ops/scripts/generate-jwt-keys.sh` (openssl) writes a 3072-bit pair into a git-ignored
    `secrets/` folder, and compose mounts it read-only. The app refuses keys shorter than 2048
    bits.
  - **No key, no start.** There is no fallback to a key generated at startup, which would hide
    a missing configuration and break every token at each restart.
  - Tests generate a fresh pair for each Spring context, so no key is ever committed.
- **Claims:**

  | Claim | Value |
  |---|---|
  | `iss` | The configured issuer, `secure-pix-ledger` by default |
  | `aud` | `secure-pix-ledger-api` |
  | `sub` | The user's UUID, never the username (no personal data in a token that clients may log) |
  | `roles` | `["CUSTOMER"]` or `["ADMIN"]`, mapped to `ROLE_*` authorities |
  | `amr` | `["pwd"]`; `["pwd","otp"]` once MFA exists (ADR-0018) |
  | `jti` | A random UUID, kept for audit and a future deny list |
  | `iat`, `exp` | `exp` is `iat` + **10 minutes** |

- **The decoder requires** a valid signature, `iss`, `aud` containing ours, and an unexpired
  `exp`. Time comes from the application's `Clock` with **60 seconds** of allowed clock skew, so
  tests can move time forward instead of sleeping.
- **Bearer header only.** Tokens travel in `Authorization: Bearer …`, never in cookies or query
  strings. With no cookies there is nothing a browser sends on its own, so CSRF protection is
  off. Sessions are `STATELESS`: no `HttpSession` and no `JSESSIONID`.
- **URL rules** live in one `SecurityFilterChain` in `infrastructure.security`:
  - `POST /auth/register` and `POST /auth/login` are public.
  - The OpenAPI docs stay public until a later Phase 2 step limits them to the `dev` profile.
  - Everything else needs a valid token. Per-role rules and ownership checks come with
    ADR-0015.
  - Actuator lives on its own unpublished port ([ADR-0005](0005-actuator-and-port-exposure.md))
    and is not affected by this chain.
- **Login:**
  - `POST /auth/login {username, password}` returns `200`
    `{accessToken, tokenType: "Bearer", expiresIn: 600}` with `Cache-Control: no-store`.
  - **One answer for every failure.** A wrong password, an unknown username, and a username or
    password that could never have been registered all get the same
    `401 invalid-credentials`.
  - **Same timing.** An unknown username is still hashed, against a fixed dummy Argon2id hash,
    so the response time does not reveal which usernames exist.
  - The password goes through the same `Password` type as registration (NFKC), and hashing runs
    outside any transaction, as in ADR-0013.
- **Errors stay RFC 9457 problems** ([ADR-0011](0011-http-error-contract.md)), written by our
  own `AuthenticationEntryPoint` and `AccessDeniedHandler`:
  - A missing or invalid token gets `401` with a fixed detail. `WWW-Authenticate` is `Bearer`,
    plus `error="invalid_token"` when a token was sent (RFC 6750), so clients know to log in
    again. There is **no `error_description`**: Spring's default says why the token failed
    (expired, bad signature, wrong audience), which helps an attacker more than a client.
  - An authenticated caller without the right role gets `403`.

## Alternatives considered

- **Opaque session tokens checked against the database on every call:** revocation would be
  immediate, but the brief asks for JWTs, and every request would cost a database read.
  Refresh tokens (ADR-0016) are opaque and stored, so revocation takes effect within one access
  token lifetime.
- **HS256 (one shared secret):** simpler keys, but anything that can verify a token can also
  mint one, and an RSA public key mistaken for an HMAC secret is a classic forgery.
- **ES256 or EdDSA:** smaller tokens and faster signing, and equally safe. RS256 has the widest
  support in libraries and tools; switching later is a key rotation, not a redesign.
- **Spring Authorization Server:** a full OAuth 2.1 server, with client registration and
  authorization-code flows we do not need. It is the right move only if third-party clients
  ever appear.
- **Tokens in an `HttpOnly` cookie:** hides them from page scripts, but brings CSRF back and
  only helps browser clients. This API has none.
- **An ephemeral key generated at startup when none is configured:** convenient for first runs,
  but every restart would log everyone out, and production could run on it without anyone
  noticing.

## Consequences

- Every endpoint except register, login and the docs now needs a token, so every existing API
  test logs in through a shared helper first.
- **Revocation is not instant.** An access token stays valid until it expires, up to 10 minutes
  after a logout, a lockout or a role change. This is accepted and recorded in the threat model.
- The private key is a new secret to protect: compose mounts it read-only, and it is never
  logged or committed. Rotation needs a second `kid` that the decoder accepts during the
  overlap; that is future work.
- Login costs one Argon2id hash even for unknown usernames, so per-IP rate limiting (ADR-0017)
  must bound it.
- Spring Security stays in the adapters ([ADR-0001](0001-hexagonal-layout.md)): controllers read
  the caller's id from the `sub` claim and pass it to the use cases as a plain `UUID`.
