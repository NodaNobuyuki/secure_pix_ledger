package dev.carlosnobuyuki.bank.infrastructure.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.converter.RsaKeyConverters;

/**
 * The RSA key pair that signs and verifies access tokens (ADR-0014). Construction fails, and with
 * it the application's startup, unless the keys are a matching pair of at least {@value #MIN_BITS}
 * bits. There is deliberately no fallback key: a missing key must stop the app, not hide behind one
 * that only looks like it works.
 */
final class JwtKeys {

  static final int MIN_BITS = 2048;

  private final RSAPublicKey publicKey;
  private final RSAPrivateKey privateKey;

  JwtKeys(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
    this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
    this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
    if (publicKey.getModulus().bitLength() < MIN_BITS) {
      throw new IllegalArgumentException("JWT keys must be at least " + MIN_BITS + " bits");
    }
    // Same modulus means same pair. A mismatch would sign tokens that no one can verify.
    if (!publicKey.getModulus().equals(privateKey.getModulus())) {
      throw new IllegalArgumentException("JWT public key does not belong to the private key");
    }
  }

  /** Reads a PKCS#8 private key and an X.509 public key, as {@code openssl} writes them. */
  static JwtKeys load(Path privateKeyPem, Path publicKeyPem) {
    return new JwtKeys(
        read(publicKeyPem, RsaKeyConverters.x509()), read(privateKeyPem, RsaKeyConverters.pkcs8()));
  }

  RSAPublicKey publicKey() {
    return publicKey;
  }

  RSAPrivateKey privateKey() {
    return privateKey;
  }

  // Error messages name the file, never its contents.
  private static <K> K read(Path pem, Converter<InputStream, K> converter) {
    try (InputStream in = Files.newInputStream(pem)) {
      return Objects.requireNonNull(converter.convert(in), "key");
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read JWT key file " + pem, e);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("JWT key file " + pem + " is not the expected PEM key", e);
    }
  }
}
