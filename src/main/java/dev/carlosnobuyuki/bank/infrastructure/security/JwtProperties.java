package dev.carlosnobuyuki.bank.infrastructure.security;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings for access tokens (ADR-0014), bound from {@code app.jwt.*}. */
@ConfigurationProperties("app.jwt")
record JwtProperties(String issuer, String audience, Path privateKeyPath, Path publicKeyPath) {}
