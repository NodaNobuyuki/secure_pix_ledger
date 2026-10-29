package dev.carlosnobuyuki.bank.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

class SecurityConfigTest {

  private final BearerTokenResolver resolver = SecurityConfig.bearerTokenResolver();

  @Test
  void rolesBecomeRoleAuthoritiesAndSubIsThePrincipalName() {
    String userId = UUID.randomUUID().toString();
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject(userId)
            .claim("roles", List.of("CUSTOMER"))
            .issuedAt(Instant.now())
            .build();

    AbstractAuthenticationToken authentication =
        SecurityConfig.jwtAuthenticationConverter().convert(jwt);

    assertThat(authentication.getName()).isEqualTo(userId);
    // Spring Security 7 also adds FACTOR_BEARER, recording how the caller authenticated.
    assertThat(authentication.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .filteredOn(authority -> authority.startsWith("ROLE_"))
        .containsExactly("ROLE_CUSTOMER");
  }

  @Test
  void readsTheTokenFromTheAuthorizationHeader() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/x");
    request.addHeader("Authorization", "Bearer abc.def.ghi");

    assertThat(resolver.resolve(request)).isEqualTo("abc.def.ghi");
  }

  @Test
  void ignoresTokensInParameters() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/x");
    request.setParameter("access_token", "abc.def.ghi");

    assertThat(resolver.resolve(request)).isNull();
  }

  @Test
  void ignoresTokensSentToPublicEndpoints() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
    request.addHeader("Authorization", "Bearer stale");

    assertThat(resolver.resolve(request)).isNull();
  }
}
