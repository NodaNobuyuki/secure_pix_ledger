package dev.carlosnobuyuki.bank.application.port;

import dev.carlosnobuyuki.bank.domain.auth.Password;
import dev.carlosnobuyuki.bank.domain.auth.PasswordHash;

/** One-way, salted, deliberately slow password hashing (ADR-0013). */
public interface PasswordHasher {

  PasswordHash hash(Password password);

  /** Hashes the password with the salt and parameters stored in {@code hash} and compares. */
  boolean matches(Password password, PasswordHash hash);
}
