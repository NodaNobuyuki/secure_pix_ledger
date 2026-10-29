package dev.carlosnobuyuki.bank;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * RSA key pairs for tests, written as PEM files in the same formats as {@code openssl}. Tests
 * generate their keys, so no key is ever committed (ADR-0014).
 */
public final class TestJwtKeys {

  private TestJwtKeys() {}

  public static KeyPair generate(int bits) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(bits);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** PKCS#8, like {@code openssl genpkey}. */
  public static Path writePrivateKey(KeyPair pair, Path file) {
    return writePem(file, "PRIVATE KEY", pair.getPrivate().getEncoded());
  }

  /** X.509 SubjectPublicKeyInfo, like {@code openssl pkey -pubout}. */
  public static Path writePublicKey(KeyPair pair, Path file) {
    return writePem(file, "PUBLIC KEY", pair.getPublic().getEncoded());
  }

  private static Path writePem(Path file, String label, byte[] der) {
    String body =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
    String pem = "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    try {
      return Files.writeString(file, pem, StandardCharsets.US_ASCII);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
