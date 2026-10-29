package dev.carlosnobuyuki.bank.infrastructure.security;

import dev.carlosnobuyuki.bank.application.port.AccessToken;
import dev.carlosnobuyuki.bank.application.port.AccessTokenIssuer;
import dev.carlosnobuyuki.bank.domain.auth.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Signs access tokens as RS256 JWTs (ADR-0014). Clients may log tokens, so the claims name the user
 * by id only, never by username.
 */
@Component
class JwtAccessTokenIssuer implements AccessTokenIssuer {

  /** RFC 9068 media type; the decoder requires it, so no other JWT passes as an access token. */
  static final String TYPE = "at+jwt";

  static final Duration TTL = Duration.ofMinutes(10);

  static final String ROLES_CLAIM = "roles";
  static final String AMR_CLAIM = "amr";
  // RFC 8176 method for "password". MFA adds "otp" (ADR-0018).
  static final String PASSWORD_METHOD = "pwd";

  private final JwtEncoder encoder;
  private final JwtProperties properties;
  private final Clock clock;

  JwtAccessTokenIssuer(JwtEncoder encoder, JwtProperties properties, Clock clock) {
    this.encoder = encoder;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public AccessToken issue(User user) {
    // JWT times are whole seconds.
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type(TYPE).build();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .audience(List.of(properties.audience()))
            .subject(user.id().toString())
            .claim(ROLES_CLAIM, List.of(user.role().name()))
            .claim(AMR_CLAIM, List.of(PASSWORD_METHOD))
            .id(UUID.randomUUID().toString())
            .issuedAt(now)
            .expiresAt(now.plus(TTL))
            .build();
    String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new AccessToken(token, TTL);
  }
}
