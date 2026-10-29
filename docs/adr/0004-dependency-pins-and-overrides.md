# ADR-0004: Pin tool versions and override vulnerable managed dependencies

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

Spring Boot manages most dependency versions through its parent POM. That is usually what we
want, but two situations came up during bootstrap:

1. **Tomcat CVEs.** Boot 4.1.1 manages Tomcat 11.0.24, which is affected by several critical
   CVEs (CVSS 9.1–9.8, including CVE-2026-65637 and CVE-2026-65905) fixed in 11.0.25.
   The Dependency-Check gate (ADR-0003) failed the build, correctly.
2. **Formatter incompatibility.** google-java-format 1.37.x breaks Spotless 3.10.4 through a
   reflection API mismatch, so letting the formatter float would break `./mvnw verify`.

## Decision

- **Override Tomcat:** set `<tomcat.version>11.0.26</tomcat.version>` in `pom.xml`. The property
  is Boot's own version knob, so every Tomcat artifact moves together.
- **Pin the formatter:** `google-java-format` is fixed at **1.36.1**, the version Spotless 3.10.4
  is tested with.
- All quality and security plugins (Spotless, SpotBugs, FindSecBugs, Dependency-Check) are
  pinned through `pom.xml` properties, each with a comment when the pin has a reason.
- *Note (2026-10-29): Swagger UI's DOMPurify is suppressed, not overridden.* springdoc 3.1.1
  brings the `swagger-ui` 5.32.14 webjar, which bundles DOMPurify 3.4.13. Dependency-Check
  reports five CVEs against it (CVE-2026-116803 and -116804 at CVSS 10), all fixed in DOMPurify
  3.4.17. An override can't help: the newest webjar, 5.34.0, still bundles 3.4.16. Unlike the
  Tomcat case, the code is not reachable here. Each CVE needs a DOMPurify mode Swagger UI never
  uses (`IN_PLACE`, `SAFE_FOR_TEMPLATES`, XHTML parsing, or a hook that removes nodes), and it
  only sanitizes text from our own OpenAPI document. So `dependency-check-suppressions.xml`
  suppresses them **until 2026-11-28**, for that exact DOMPurify version only.

## Alternatives considered

- **Suppress the Tomcat CVEs:** the vulnerable code runs in our process on every request; there is
  no argument for "does not apply".
- **Wait for a Boot release:** leaves a known critical vulnerability in place.
- **Upgrade Spotless instead of pinning the formatter:** no compatible Spotless release was
  available at the time.

## Consequences

- Overrides are debt: Boot no longer guarantees that this Tomcat version was tested with it.
  **Remove the `tomcat.version` override as soon as a Boot release manages Tomcat ≥ 11.0.25.**
- Revisit the google-java-format pin when Spotless is upgraded.
- **Drop the DOMPurify suppression as soon as a springdoc or `swagger-ui` release bundles
  DOMPurify ≥ 3.4.17.** If none exists when it expires, the gate fails again and the risk is
  re-reviewed. Phase 2 Step 9 also limits Swagger UI to the `dev` profile.
- Builds are reproducible: the same commit resolves the same tool versions.
