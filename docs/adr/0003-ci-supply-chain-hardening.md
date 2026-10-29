# ADR-0003: Harden the CI pipeline and make security checks blocking

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

CI runs third-party code (GitHub Actions, Maven plugins) with access to the repository and its
secrets. A compromised action or an over-privileged token is a supply-chain attack path.
Security checks that only produce reports also tend to be ignored; for a banking API we want
them to stop the merge.

## Decision

Workflows: `.github/workflows/ci.yml` and `.github/workflows/codeql.yml`.

**Least privilege**
- Workflow-level `permissions: contents: read`; jobs ask for more only when needed
  (CodeQL alone gets `security-events: write`).
- `actions/checkout` uses `persist-credentials: false`, so the token is not left in `.git/config`.

**Pinned dependencies**
- Every third-party action is pinned to a full commit SHA, with the tag as a comment.
  Tags are mutable; a SHA is not.
- Maven plugin versions are pinned in `pom.xml` properties (see [ADR-0004](0004-dependency-pins-and-overrides.md)).

**Blocking gates** (any failure fails the pipeline)

| Gate | Where | Fails on |
|---|---|---|
| Tests, incl. Testcontainers | `./mvnw verify` | any failing test |
| Spotless (google-java-format) | `verify` phase | unformatted code |
| SpotBugs + FindSecBugs | `verify` phase | findings at Medium or above |
| OWASP Dependency-Check | `dependency-check` job | any dependency CVE with CVSS ≥ 7 |
| CodeQL (`security-extended`) | `codeql.yml` | **any** finding (explicit SARIF check, since the action only reports by default), with one exception below |

**Operational details**
- A weekly scheduled run catches CVEs and new CodeQL queries even when code does not change.
- The NVD database is cached separately and saved even when the scan fails; otherwise the job
  that finds a CVE would force a full re-download on every run.
- The `NVD_API_KEY` secret is required; the job fails fast with a clear message if it is missing.
- Suppressions live in `spotbugs-exclude.xml` and `dependency-check-suppressions.xml`, each entry
  with a written reason (and an `until` date for CVEs).
- *Note (2026-10-29):* the CodeQL gate ignores one rule, `java/insufficient-key-size`, and only
  under `src/test/`. `JwtKeysTest` builds a 1024-bit RSA key on purpose, to prove that keys under
  2048 bits are rejected ([ADR-0014](0014-jwt-access-tokens.md)). Production code is still
  checked by every rule.

## Alternatives considered

- **Tags such as `@v4`:** easier to read and update, but trust the tag owner forever.
- **Non-blocking reports:** less friction, but findings pile up unread.

## Consequences

- Updating an action means updating a SHA. Dependabot for `github-actions` can automate this
  later.
- A new CVE in a transitive dependency can turn `main` red without any code change. That is
  intended: it is fixed with an upgrade, an override (as in ADR-0004) or a justified suppression.
