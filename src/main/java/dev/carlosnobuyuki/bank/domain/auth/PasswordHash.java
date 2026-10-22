package dev.carlosnobuyuki.bank.domain.auth;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An encoded password hash that names its algorithm, such as {@code {argon2}$argon2id$...}
 * (ADR-0013). Requiring the prefix keeps a plaintext password from being stored as a hash by
 * mistake; the database checks the same rule.
 */
public record PasswordHash(String encoded) {

  private static final Pattern ENCODED = Pattern.compile("\\{[a-z0-9]+\\}.+");

  public PasswordHash {
    Objects.requireNonNull(encoded, "encoded");
    if (!ENCODED.matcher(encoded).matches()) {
      throw new IllegalArgumentException("password hash must start with an {algorithm} prefix");
    }
  }

  // Not a secret like the password, but offline cracking starts from it, so keep it out of logs.
  @Override
  public String toString() {
    return "PasswordHash[***]";
  }
}
