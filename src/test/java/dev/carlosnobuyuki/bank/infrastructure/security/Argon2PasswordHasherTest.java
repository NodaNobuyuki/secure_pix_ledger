package dev.carlosnobuyuki.bank.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.carlosnobuyuki.bank.domain.auth.Password;
import dev.carlosnobuyuki.bank.domain.auth.PasswordHash;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class Argon2PasswordHasherTest {

  private static final Password PASSWORD = new Password("correct horse battery staple");

  private final Argon2PasswordHasher hasher = new Argon2PasswordHasher();

  @Test
  void usesArgon2idWithOwaspParameters() {
    assertThat(hasher.hash(PASSWORD).encoded())
        .startsWith("{argon2}$argon2id$v=19$m=19456,t=2,p=1$");
  }

  @Test
  void producesAHashThatVerifiesOnlyTheSamePassword() {
    String encoded = hasher.hash(PASSWORD).encoded().substring("{argon2}".length());
    Argon2PasswordEncoder verifier = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);

    assertThat(verifier.matches(PASSWORD.value(), encoded)).isTrue();
    assertThat(verifier.matches("correct horse battery stapler", encoded)).isFalse();
  }

  @Test
  void matchesOnlyTheSamePassword() {
    PasswordHash hash = hasher.hash(PASSWORD);

    assertThat(hasher.matches(PASSWORD, hash)).isTrue();
    assertThat(hasher.matches(new Password("correct horse battery stapler"), hash)).isFalse();
  }

  @Test
  void matchingReadsTheParametersFromTheHash() {
    // A hash made with other settings still verifies, so the parameters can be raised later.
    String weaker = new Argon2PasswordEncoder(16, 32, 1, 1024, 1).encode(PASSWORD.value());

    assertThat(hasher.matches(PASSWORD, new PasswordHash("{argon2}" + weaker))).isTrue();
  }

  @Test
  void saltsEveryHash() {
    assertThat(hasher.hash(PASSWORD)).isNotEqualTo(hasher.hash(PASSWORD));
  }

  @Test
  void neverContainsThePassword() {
    assertThat(hasher.hash(PASSWORD).encoded()).doesNotContain("horse");
  }
}
