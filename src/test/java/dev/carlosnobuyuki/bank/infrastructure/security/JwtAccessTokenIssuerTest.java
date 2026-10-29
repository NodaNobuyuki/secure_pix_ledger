package dev.carlosnobuyuki.bank.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.RSAKey;
import dev.carlosnobuyuki.bank.TestJwtKeys;
import dev.carlosnobuyuki.bank.application.port.AccessToken;
import dev.carlosnobuyuki.bank.domain.auth.PasswordHash;
import dev.carlosnobuyuki.bank.domain.auth.User;
import dev.carlosnobuyuki.bank.domain.auth.Username;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtAccessTokenIssuerTest {

  static final JwtProperties PROPERTIES =
      new JwtProperties("test-issuer", "test-audience", null, null);

  private static final KeyPair PAIR = TestJwtKeys.generate(2048);
  private static final JwtKeys KEYS =
      new JwtKeys((RSAPublicKey) PAIR.getPublic(), (RSAPrivateKey) PAIR.getPrivate());
  private static final Instant NOW = Instant.parse("2026-10-29T12:00:00.750Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final JwtConfig config = new JwtConfig();
  private final JwtAccessTokenIssuer issuer =
      new JwtAccessTokenIssuer(config.jwtEncoder(KEYS), PROPERTIES, CLOCK);

  private final User user =
      User.register(
          UUID.randomUUID(),
          Username.parse("alice"),
          new PasswordHash("{argon2}not-a-real-hash"),
          NOW);

  @Test
  void signsWithRs256AndTheKeyThumbprintAsKid() throws Exception {
    Jwt jwt = decode(issuer.issue(user));

    String thumbprint = new RSAKey.Builder(KEYS.publicKey()).build().computeThumbprint().toString();
    assertThat(jwt.getHeaders())
        .containsEntry("alg", "RS256")
        .containsEntry("typ", "at+jwt")
        .containsEntry("kid", thumbprint);
  }

  @Test
  void carriesTheClaimsTheAdrLists() {
    Jwt jwt = decode(issuer.issue(user));

    // Not getIssuer(), which insists on a URL; iss may be any string (RFC 7519).
    assertThat(jwt.getClaimAsString("iss")).isEqualTo("test-issuer");
    assertThat(jwt.getAudience()).containsExactly("test-audience");
    assertThat(jwt.getSubject()).isEqualTo(user.id().toString());
    assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CUSTOMER");
    assertThat(jwt.getClaimAsStringList("amr")).containsExactly("pwd");
    assertThat(jwt.getId()).isNotBlank();
  }

  @Test
  void expiresTenMinutesAfterIssue() {
    AccessToken token = issuer.issue(user);
    Jwt jwt = decode(token);

    assertThat(jwt.getIssuedAt()).isEqualTo(Instant.parse("2026-10-29T12:00:00Z"));
    assertThat(jwt.getExpiresAt()).isEqualTo(Instant.parse("2026-10-29T12:10:00Z"));
    assertThat(token.expiresIn()).isEqualTo(Duration.ofMinutes(10));
  }

  @Test
  void eachTokenHasItsOwnId() {
    assertThat(decode(issuer.issue(user)).getId()).isNotEqualTo(decode(issuer.issue(user)).getId());
  }

  @Test
  void payloadHoldsNoUsername() {
    String payload = issuer.issue(user).value().split("\\.")[1];

    assertThat(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8))
        .doesNotContain("alice");
  }

  @Test
  void toStringHidesTheToken() {
    AccessToken token = issuer.issue(user);

    assertThat(token.toString()).doesNotContain(token.value());
  }

  private Jwt decode(AccessToken token) {
    return config.jwtDecoder(KEYS, PROPERTIES, CLOCK).decode(token.value());
  }
}
