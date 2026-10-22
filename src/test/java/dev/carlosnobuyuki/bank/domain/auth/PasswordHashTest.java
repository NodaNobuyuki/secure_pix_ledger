package dev.carlosnobuyuki.bank.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordHashTest {

  @Test
  void acceptsEncodedHashThatNamesItsAlgorithm() {
    assertThat(new PasswordHash("{argon2}$argon2id$v=19$abc").encoded()).startsWith("{argon2}");
  }

  @Test
  void rejectsBareStringsThatCouldBePlaintext() {
    assertThatThrownBy(() -> new PasswordHash("correct horse battery"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PasswordHash("{argon2}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void neverPrintsTheValue() {
    assertThat(new PasswordHash("{argon2}$argon2id$secret").toString()).doesNotContain("secret");
  }
}
