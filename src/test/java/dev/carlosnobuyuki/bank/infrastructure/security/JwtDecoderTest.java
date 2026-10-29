package dev.carlosnobuyuki.bank.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.carlosnobuyuki.bank.TestJwtKeys;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** The known ways to get a forged or stale JWT accepted (RFC 8725), each one refused. */
class JwtDecoderTest {

  private static final KeyPair PAIR = TestJwtKeys.generate(2048);
  private static final JwtKeys KEYS = keys(PAIR);
  private static final Instant ISSUED = Instant.parse("2026-10-29T12:00:00Z");
  private static final Instant EXPIRES = ISSUED.plus(Duration.ofMinutes(10));

  private final JwtConfig config = new JwtConfig();
  private final JwtEncoder encoder = config.jwtEncoder(KEYS);

  @Test
  void acceptsAValidToken() {
    assertThat(decoderAt(ISSUED).decode(sign(claims -> {})).getSubject()).isNotBlank();
  }

  @Test
  void acceptsAnExpiredTokenWithinTheClockSkew() {
    assertThat(decoderAt(EXPIRES.plusSeconds(59)).decode(sign(claims -> {}))).isNotNull();
  }

  @Test
  void rejectsAnExpiredToken() {
    assertRejected(decoderAt(EXPIRES.plusSeconds(61)), sign(claims -> {}));
  }

  @Test
  void rejectsATokenWithoutExpiry() {
    assertRejected(sign(claims -> claims.remove(JwtClaimNames.EXP)));
  }

  @Test
  void rejectsATamperedPayload() {
    String[] parts = sign(claims -> {}).split("\\.");
    String forged = base64Url("{\"sub\":\"" + UUID.randomUUID() + "\",\"roles\":[\"ADMIN\"]}");

    assertRejected(parts[0] + "." + forged + "." + parts[2]);
  }

  @Test
  void rejectsATokenSignedWithAnotherKey() {
    JwtEncoder attacker = config.jwtEncoder(keys(TestJwtKeys.generate(2048)));

    assertRejected(
        attacker.encode(JwtEncoderParameters.from(header(), validClaims())).getTokenValue());
  }

  @Test
  void rejectsAnUnsignedToken() {
    String header = base64Url("{\"alg\":\"none\",\"typ\":\"at+jwt\"}");
    String[] parts = sign(claims -> {}).split("\\.");

    assertRejected(header + "." + parts[1] + ".");
  }

  @Test
  void rejectsAnHmacTokenKeyedWithThePublicKey() {
    // The classic confusion attack: the public key is public, so if a verifier let the token pick
    // HS256, anyone could use it as the HMAC secret.
    SecretKeySpec publicKeyAsSecret =
        new SecretKeySpec(PAIR.getPublic().getEncoded(), "HmacSHA256");
    JwtEncoder hmac = NimbusJwtEncoder.withSecretKey(publicKeyAsSecret).build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("at+jwt").build();

    assertRejected(hmac.encode(JwtEncoderParameters.from(header, validClaims())).getTokenValue());
  }

  @Test
  void rejectsAnotherTokenType() {
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();

    assertRejected(
        encoder.encode(JwtEncoderParameters.from(header, validClaims())).getTokenValue());
  }

  @Test
  void rejectsAnotherIssuer() {
    assertRejected(sign(claims -> claims.put(JwtClaimNames.ISS, "someone-else")));
  }

  @Test
  void rejectsAnotherAudience() {
    assertRejected(sign(claims -> claims.put(JwtClaimNames.AUD, List.of("another-api"))));
  }

  @Test
  void rejectsATokenWithoutSubject() {
    assertRejected(sign(claims -> claims.remove(JwtClaimNames.SUB)));
  }

  private void assertRejected(String token) {
    assertRejected(decoderAt(ISSUED), token);
  }

  private static void assertRejected(JwtDecoder decoder, String token) {
    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }

  private JwtDecoder decoderAt(Instant now) {
    return config.jwtDecoder(
        KEYS, JwtAccessTokenIssuerTest.PROPERTIES, Clock.fixed(now, ZoneOffset.UTC));
  }

  // A valid token, changed by the given edit to its claims.
  private String sign(Consumer<Map<String, Object>> edit) {
    JwtClaimsSet claims = JwtClaimsSet.from(validClaims()).claims(edit).build();
    return encoder.encode(JwtEncoderParameters.from(header(), claims)).getTokenValue();
  }

  private static JwsHeader header() {
    return JwsHeader.with(SignatureAlgorithm.RS256).type("at+jwt").build();
  }

  private static JwtClaimsSet validClaims() {
    return JwtClaimsSet.builder()
        .issuer("test-issuer")
        .audience(List.of("test-audience"))
        .subject(UUID.randomUUID().toString())
        .issuedAt(ISSUED)
        .expiresAt(EXPIRES)
        .build();
  }

  private static JwtKeys keys(KeyPair pair) {
    return new JwtKeys((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
  }

  private static String base64Url(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
