package dev.carlosnobuyuki.bank.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UsernameTest {

  @Test
  void normalizesToLowercase() {
    assertThat(Username.parse("Alice.Smith_1-x").value()).isEqualTo("alice.smith_1-x");
    assertThat(Username.parse("ALICE")).isEqualTo(Username.parse("alice"));
  }

  @Test
  void acceptsThreeToThirtyTwoCharacters() {
    assertThat(Username.parse("abc").value()).hasSize(3);
    assertThat(Username.parse("a".repeat(32)).value()).hasSize(32);
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(
      strings = {
        "",
        "ab",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", // 33
        "has space",
        " alice",
        "alice@example.com",
        "émile",
        "аlice", // Cyrillic "а", looks like Latin "a"
        "İstanbul", // Turkish dotted I lowercases to "i" plus a combining dot
        "<script>"
      })
  void rejectsMalformedNamesWithoutEchoingThem(String raw) {
    // A fixed message: the rejected input can never appear in it.
    assertThatThrownBy(() -> Username.parse(raw))
        .isInstanceOf(InvalidUsernameException.class)
        .hasMessage("must be 3 to 32 characters: letters, digits, '.', '_' or '-'");
  }

  @Test
  void constructorRequiresTheNormalizedForm() {
    assertThatThrownBy(() -> new Username("Alice")).isInstanceOf(InvalidUsernameException.class);
  }
}
