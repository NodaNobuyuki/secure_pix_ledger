package dev.carlosnobuyuki.bank.api;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Turns the authenticated token into the user id the use cases take (ADR-0001, ADR-0015). */
final class Callers {

  private Callers() {}

  /** Our tokens carry the user id as {@code sub} (ADR-0014). */
  static UUID id(Jwt token) {
    String subject = token.getSubject();
    // The decoder already rejects tokens without sub; reaching this means that check was removed.
    if (subject == null) {
      throw new IllegalStateException("access token has no subject");
    }
    return UUID.fromString(subject);
  }
}
