package dev.carlosnobuyuki.bank.infrastructure.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Keys, signing and verification of access tokens (ADR-0014). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
class JwtConfig {

  static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

  @Bean
  JwtKeys jwtKeys(JwtProperties properties) {
    return JwtKeys.load(properties.privateKeyPath(), properties.publicKeyPath());
  }

  /** Signs with RS256 and puts the key's RFC 7638 thumbprint in the {@code kid} header. */
  @Bean
  JwtEncoder jwtEncoder(JwtKeys keys) {
    return NimbusJwtEncoder.withKeyPair(keys.publicKey(), keys.privateKey())
        .algorithm(SignatureAlgorithm.RS256)
        .build();
  }

  /**
   * Accepts only what {@link JwtAccessTokenIssuer} issues. The algorithm is fixed here, never taken
   * from the token, which rules out {@code alg: none} and RSA/HMAC confusion (RFC 8725).
   */
  @Bean
  JwtDecoder jwtDecoder(JwtKeys keys, JwtProperties properties, Clock clock) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey(keys.publicKey())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
    JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
    timestamps.setClock(clock);
    // Spring accepts a token without exp by default, and such a token would never expire.
    timestamps.setAllowEmptyExpiryClaim(false);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTypeValidator(JwtAccessTokenIssuer.TYPE),
            timestamps,
            new JwtIssuerValidator(properties.issuer()),
            new JwtAudienceValidator(properties.audience()),
            // Controllers take the caller's id from sub, so a token must name one.
            new JwtClaimValidator<Object>(JwtClaimNames.SUB, Objects::nonNull)));
    return decoder;
  }
}
