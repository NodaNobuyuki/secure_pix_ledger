# ADR-0002: Target Spring Boot 4.1 instead of 3.x

- **Status:** Accepted
- **Date:** 2026-10-12

## Context

The project brief specifies Java 21 and Spring Boot 3.x. When the project started (October 2026),
open-source support for Spring Boot 3.5, the last 3.x line, had already ended (2026-06-30).
After that date, security fixes for 3.x ship only through commercial support.

A security-focused portfolio project that starts on an unsupported framework line would
undercut its own message, and would immediately need a major-version migration.

## Decision

Use **Spring Boot 4.1.x** (currently 4.1.1) on **Java 21 (Temurin)**, which is pinned through
`mise.toml`, CI (`actions/setup-java`) and the Docker base images.

## Alternatives considered

- **Spring Boot 3.5.x as written in the brief:** matches the brief, but receives no more
  open-source security patches.
- **Java 25:** newer LTS, but the brief and the target job market use 21.

## Consequences

- We stay on a supported line and receive security fixes through normal upgrades.
- Boot 4 is built on Spring Framework 7 and Jakarta EE 11. Most tutorials and Stack Overflow
  answers still show 3.x, so some APIs differ (for example, starters split by technology such as
  `spring-boot-starter-webmvc` and `spring-boot-starter-flyway`, with matching `-test` starters).
- Third-party libraries must be checked for Boot 4 compatibility before adding them
  (for example, springdoc-openapi needs its 3.x line).
