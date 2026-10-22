package dev.carlosnobuyuki.bank.domain.auth;

import java.text.Normalizer;
import java.util.Objects;

/**
 * A plaintext password on its way to the hasher (ADR-0013). NFKC-normalized, so the same password
 * typed with differently composed characters hashes the same; its length is counted in code points,
 * as NIST SP 800-63B asks. {@link #toString()} hides it, so it cannot end up in a log by accident.
 */
public record Password(String value) {

  public static final int MIN_LENGTH = 12;
  // Caps the hashing work a single request can cause.
  public static final int MAX_LENGTH = 128;

  public Password {
    Objects.requireNonNull(value, "value");
    value = Normalizer.normalize(value, Normalizer.Form.NFKC);
    int length = value.codePointCount(0, value.length());
    if (length < MIN_LENGTH || length > MAX_LENGTH) {
      throw new InvalidPasswordException();
    }
  }

  @Override
  public String toString() {
    return "Password[***]";
  }
}
