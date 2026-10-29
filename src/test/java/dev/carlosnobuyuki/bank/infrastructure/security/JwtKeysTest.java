package dev.carlosnobuyuki.bank.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.carlosnobuyuki.bank.TestJwtKeys;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.KeyPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JwtKeysTest {

  // Key generation is the slow part, so one pair serves every test that needs a valid one.
  private static final KeyPair PAIR = TestJwtKeys.generate(2048);

  @TempDir Path dir;

  @Test
  void loadsAMatchingPairFromPemFiles() {
    JwtKeys keys = JwtKeys.load(privateKeyFile(PAIR), publicKeyFile(PAIR));

    assertThat(keys.publicKey()).isEqualTo(PAIR.getPublic());
    assertThat(keys.privateKey()).isEqualTo(PAIR.getPrivate());
  }

  @Test
  void missingFileFailsAndNamesIt() {
    Path missing = dir.resolve("absent.pem");

    assertThatThrownBy(() -> JwtKeys.load(missing, publicKeyFile(PAIR)))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining(missing.toString());
  }

  @Test
  void rejectsKeysShorterThan2048Bits() {
    KeyPair weak = TestJwtKeys.generate(1024);

    assertThatThrownBy(() -> JwtKeys.load(privateKeyFile(weak), publicKeyFile(weak)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("2048");
  }

  @Test
  void rejectsAPublicKeyFromAnotherPair() {
    KeyPair other = TestJwtKeys.generate(2048);

    assertThatThrownBy(() -> JwtKeys.load(privateKeyFile(PAIR), publicKeyFile(other)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not belong");
  }

  @Test
  void rejectsAPublicKeyWhereThePrivateKeyBelongs() {
    Path publicKey = publicKeyFile(PAIR);

    assertThatThrownBy(() -> JwtKeys.load(publicKey, publicKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(publicKey.toString());
  }

  private Path privateKeyFile(KeyPair pair) {
    return TestJwtKeys.writePrivateKey(pair, dir.resolve("private-" + pair.hashCode() + ".pem"));
  }

  private Path publicKeyFile(KeyPair pair) {
    return TestJwtKeys.writePublicKey(pair, dir.resolve("public-" + pair.hashCode() + ".pem"));
  }
}
