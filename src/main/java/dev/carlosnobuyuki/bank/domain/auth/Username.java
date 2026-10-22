package dev.carlosnobuyuki.bank.domain.auth;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A login name in normalized form (ADR-0013): 3 to 32 lowercase ASCII letters, digits, '.', '_' or
 * '-'. Normalizing at the edge makes uniqueness case-insensitive, and ASCII only rules out
 * look-alike characters from other scripts.
 */
public record Username(String value) {

  private static final Pattern FORMAT = Pattern.compile("[a-z0-9._-]{3,32}");

  public Username {
    Objects.requireNonNull(value, "value");
    if (!FORMAT.matcher(value).matches()) {
      throw new InvalidUsernameException();
    }
  }

  /** Normalizes user input, so "Alice" and "alice" are the same user. */
  public static Username parse(String raw) {
    Objects.requireNonNull(raw, "raw");
    return new Username(raw.toLowerCase(Locale.ROOT));
  }
}
