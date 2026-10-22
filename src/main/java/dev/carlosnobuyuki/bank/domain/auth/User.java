package dev.carlosnobuyuki.bank.domain.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Someone who can log in. Holds only the password's hash, never the password (ADR-0013). */
public final class User {

  private final UUID id;
  private final Username username;
  private final PasswordHash passwordHash;
  private final Role role;
  private final Instant createdAt;

  private User(
      UUID id, Username username, PasswordHash passwordHash, Role role, Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.username = Objects.requireNonNull(username, "username");
    this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
    this.role = Objects.requireNonNull(role, "role");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  /** Self-registration: always a customer, whatever the request contained. */
  public static User register(UUID id, Username username, PasswordHash passwordHash, Instant now) {
    return new User(id, username, passwordHash, Role.CUSTOMER, now);
  }

  public UUID id() {
    return id;
  }

  public Username username() {
    return username;
  }

  public PasswordHash passwordHash() {
    return passwordHash;
  }

  public Role role() {
    return role;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
