package dev.carlosnobuyuki.bank.infrastructure.security;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Who may call what (ADR-0014). Every request needs a valid access token except login, registration
 * and the API docs.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  /** Reachable without a token. */
  static final RequestMatcher PUBLIC =
      new OrRequestMatcher(
          pathPattern(HttpMethod.POST, "/auth/register"),
          pathPattern(HttpMethod.POST, "/auth/login"),
          // Public until a later Phase 2 step limits the docs to the dev profile.
          pathPattern(HttpMethod.GET, "/v3/api-docs/**"),
          pathPattern(HttpMethod.GET, "/swagger-ui/**"));

  /**
   * Actuator. With its own port, Boot installs the security filter there too, so without this chain
   * the main one would demand a token from the healthcheck and Prometheus. Port isolation is what
   * protects these endpoints (ADR-0005); anything else exposed later is denied by default.
   */
  @Bean
  @Order(1)
  SecurityFilterChain actuatorChain(HttpSecurity http) {
    http.securityMatcher(EndpointRequest.toAnyEndpoint())
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(EndpointRequest.to("health", "info", "prometheus"))
                    .permitAll()
                    .anyRequest()
                    .denyAll());
    return stateless(http).build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain apiChain(
      HttpSecurity http,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
    // Spring Security's errors go through ApiExceptionHandler like every other error, so all
    // responses follow one contract (ADR-0011).
    AuthenticationEntryPoint unauthenticated =
        (request, response, e) -> exceptionResolver.resolveException(request, response, null, e);
    AccessDeniedHandler forbidden =
        (request, response, e) -> exceptionResolver.resolveException(request, response, null, e);

    http.authorizeHttpRequests(
            requests ->
                requests
                    // The container's error page for a request already let through. Without
                    // this, an error on a public endpoint called without a token would be
                    // reported as 401 instead of its real status.
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(PUBLIC)
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    .bearerTokenResolver(bearerTokenResolver())
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                    .authenticationEntryPoint(unauthenticated)
                    .accessDeniedHandler(forbidden))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(unauthenticated)
                    .accessDeniedHandler(forbidden));
    return stateless(http).build();
  }

  /**
   * Reads the token from the {@code Authorization} header only; the default resolver already
   * ignores query strings and form fields, where tokens end up in logs and browser history. Public
   * endpoints ignore any token: a stale one left in a client's default headers must not make login
   * itself fail.
   */
  static BearerTokenResolver bearerTokenResolver() {
    DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
    return request -> PUBLIC.matches(request) ? null : header.resolve(request);
  }

  /** Maps the {@code roles} claim to {@code ROLE_*} authorities; the principal's name is sub. */
  static JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
    roles.setAuthoritiesClaimName(JwtAccessTokenIssuer.ROLES_CLAIM);
    roles.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(roles);
    return converter;
  }

  // No cookies anywhere, so there is no session to keep and nothing for CSRF to forge (ADR-0014).
  private static HttpSecurity stateless(HttpSecurity http) {
    return http.sessionManagement(
            sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable);
  }
}
