package dev.carlosnobuyuki.bank.api;

import dev.carlosnobuyuki.bank.domain.auth.User;
import java.util.UUID;

/** Echoes the normalized username, so the client learns that "Alice" was stored as "alice". */
record RegistrationResponse(UUID id, String username) {

  static RegistrationResponse from(User user) {
    return new RegistrationResponse(user.id(), user.username().value());
  }
}
