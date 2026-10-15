# ADR-0005: Isolate the actuator and minimize exposed ports

- **Status:** Accepted
- **Date:** 2026-10-12

## Context

Spring Boot Actuator exposes operational data (health details, metrics, environment). On the same
port as the public API, a misconfigured exposure list or a future endpoint becomes reachable by
any client. The local stack also runs PostgreSQL, Prometheus and Grafana, which should never be
reachable from the local network, and should never start with default credentials.

## Decision

- **Separate management port.** Actuator listens on `management.server.port` (default `8081`),
  apart from the API on `8080`. Only `health`, `info` and `prometheus` are exposed.
- **Management port is not published.** In `docker-compose.yml`, only `8080` is published for the
  app. Prometheus scrapes `app:8081` over the internal compose network.
- **Loopback-only host ports.** Every published port binds to `127.0.0.1`, never `0.0.0.0`.
- **No default secrets.** Compose uses `${VAR:?message}`, so it refuses to start when `.env` is
  missing a credential, instead of falling back to a weak default. `.env` is git-ignored and
  `.env.example` holds placeholders only.
- **Non-root container.** The runtime image runs the JVM as an unprivileged `app` user, from a
  JRE-only image with no build tools or sources.

## Alternatives considered

- **Same port with Spring Security rules on `/actuator/**`:** works, but one rule mistake exposes
  it. Network separation still holds if the application config is wrong.
- **Disable the actuator:** we need health checks and metrics for Phase 4 (observability).

## Consequences

- `curl localhost:8080/actuator/health` does **not** work by design. Health is checked by the
  compose healthcheck (from inside the container) and by the Prometheus target status at
  `http://localhost:9090/targets`.
- A real deployment would keep the same split: the management port is reachable only from the
  monitoring network.
