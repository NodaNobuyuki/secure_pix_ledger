package dev.carlosnobuyuki.bank.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordTest {

  // U+1F512 (lock): one code point, two UTF-16 chars.
  private static final String LOCK = "🔒";

  @Test
  void acceptsTwelveToOneHundredTwentyEightCharacters() {
    assertThat(new Password("a".repeat(12)).value()).hasSize(12);
    assertThat(new Password("a".repeat(128)).value()).hasSize(128);
  }

  @Test
  void rejectsTooShortOrTooLong() {
    assertThatThrownBy(() -> new Password("a".repeat(11)))
        .isInstanceOf(InvalidPasswordException.class)
        .hasMessage("must be 12 to 128 characters long");
    assertThatThrownBy(() -> new Password("a".repeat(129)))
        .isInstanceOf(InvalidPasswordException.class);
  }

  @Test
  void countsCodePointsNotUtf16Chars() {
    assertThat(new Password(LOCK.repeat(12)).value().codePointCount(0, 24)).isEqualTo(12);
    // Twelve UTF-16 chars, but only six characters.
    assertThatThrownBy(() -> new Password(LOCK.repeat(6)))
        .isInstanceOf(InvalidPasswordException.class);
  }

  @Test
  void normalizesSoEquivalentInputsAreTheSamePassword() {
    // "é" as one code point vs "e" + combining acute accent.
    assertThat(new Password("café-password1")).isEqualTo(new Password("café-password1"));
    // Compatibility forms too: fullwidth "Ａ" is "A".
    assertThat(new Password("Ａbcdefghijkl")).isEqualTo(new Password("Abcdefghijkl"));
  }

  @Test
  void neverPrintsTheValue() {
    assertThat(new Password("correct horse battery").toString()).doesNotContain("horse");
  }
}
