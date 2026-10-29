package dev.carlosnobuyuki.bank.application.port;

import java.time.Duration;
import java.util.Objects;

/**
 * A signed access token and how long it stays valid (ADR-0014). Whoever holds a bearer token acts
 * as its user until it expires, so {@link #toString()} hides it.
 */
public record AccessToken(String value, Duration expiresIn) {

  public AccessToken {
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(expiresIn, "expiresIn");
  }

  @Override
  public String toString() {
    return "AccessToken[***]";
  }
}
