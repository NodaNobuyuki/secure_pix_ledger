package dev.carlosnobuyuki.bank.infrastructure.security;

import dev.carlosnobuyuki.bank.application.port.PasswordHasher;
import dev.carlosnobuyuki.bank.domain.auth.Password;
import dev.carlosnobuyuki.bank.domain.auth.PasswordHash;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id with the OWASP minimum parameters (ADR-0013). The delegating encoder prefixes every hash
 * with its algorithm ({@code {argon2}...}), so stronger settings can be rolled out later without
 * invalidating stored hashes.
 */
@Component
class Argon2PasswordHasher implements PasswordHasher {

  static final String ALGORITHM = "argon2";

  // OWASP Password Storage Cheat Sheet: m = 19 MiB, t = 2, p = 1.
  static final int SALT_BYTES = 16;
  static final int HASH_BYTES = 32;
  static final int PARALLELISM = 1;
  static final int MEMORY_KIB = 19 * 1024;
  static final int ITERATIONS = 2;

  private final PasswordEncoder encoder =
      new DelegatingPasswordEncoder(
          ALGORITHM,
          Map.of(
              ALGORITHM,
              new Argon2PasswordEncoder(
                  SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS)));

  @Override
  public PasswordHash hash(Password password) {
    // encode() may return null only for a null input, which Password rules out.
    return new PasswordHash(Objects.requireNonNull(encoder.encode(password.value()), "hash"));
  }
}
