package dev.carlosnobuyuki.bank.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.carlosnobuyuki.bank.AuthFixture;
import dev.carlosnobuyuki.bank.IntegrationTest;
import dev.carlosnobuyuki.bank.MutableClock;
import dev.carlosnobuyuki.bank.TestJwtKeys;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The API's front door (ADR-0014): which requests get through, and what the rest are told. */
@IntegrationTest
@AutoConfigureMockMvc
class SecurityApiTests {

  private static final String INVALID_TOKEN = "Bearer error=\"invalid_token\"";

  @Autowired MockMvcTester mvc;
  @Autowired AuthFixture auth;
  @Autowired MutableClock clock;
  // The application's own signing key, so a token can be correctly signed yet wrong in one claim.
  @Autowired JwtEncoder appKey;

  @Value("${app.jwt.issuer}")
  String issuer;

  @Value("${app.jwt.audience}")
  String audience;

  private String bearer;

  @BeforeEach
  void logIn() {
    bearer = auth.newCustomer().authorization();
  }

  @Test
  void aValidTokenGetsThrough() {
    assertThat(openAccount(bearer)).hasStatus(HttpStatus.CREATED);
  }

  @Test
  void noTokenIs401WithAPlainChallenge() {
    MvcTestResult result = mvc.post().uri("/accounts").exchange();

    assertThat(result)
        .hasStatus(HttpStatus.UNAUTHORIZED)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    // No error code: the client sent no token, so there is nothing to call invalid (RFC 6750).
    assertThat(result).headers().hasValue(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("A valid access token is required.");
  }

  @Test
  void unknownUrlsAre401NotNotFound() {
    // Without a token the API does not reveal which routes exist.
    assertThat(mvc.get().uri("/no-such-route")).hasStatus(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void aTokenTheAppSignedItselfIsAcceptedAsIs() {
    // Baseline for the cases below: each one changes exactly one thing in this token.
    assertThat(openAccount("Bearer " + signed(claims -> claims, "at+jwt")))
        .hasStatus(HttpStatus.CREATED);
  }

  @Test
  void everyRejectedTokenGetsTheSameAnswer() throws Exception {
    String valid = bearer.substring("Bearer ".length());
    String[] parts = valid.split("\\.");
    String adminClaims = base64Url(decode(parts[1]).replace("\"CUSTOMER\"", "\"ADMIN\""));

    Map<String, String> rejected = new LinkedHashMap<>();
    rejected.put("claims edited after signing", parts[0] + "." + adminClaims + "." + parts[2]);
    rejected.put(
        "alg none, no signature",
        base64Url("{\"alg\":\"none\",\"typ\":\"at+jwt\"}") + "." + parts[1] + ".");
    rejected.put("signed with another key", signedWithAnotherKey());
    rejected.put(
        "wrong audience", signed(claims -> claims.audience(List.of("other-api")), "at+jwt"));
    rejected.put("wrong issuer", signed(claims -> claims.issuer("someone-else"), "at+jwt"));
    rejected.put("not an access token", signed(claims -> claims, "JWT"));
    rejected.put("not a JWT", "not-a-jwt");

    String expected = null;
    for (Map.Entry<String, String> token : rejected.entrySet()) {
      MvcTestResult result = openAccount("Bearer " + token.getValue());
      assertThat(result).as(token.getKey()).hasStatus(HttpStatus.UNAUTHORIZED);
      assertThat(result)
          .as(token.getKey())
          .headers()
          .hasValue(HttpHeaders.WWW_AUTHENTICATE, INVALID_TOKEN);
      String body = result.getResponse().getContentAsString();
      expected = expected == null ? body : expected;
      assertThat(body).as(token.getKey()).isEqualTo(expected);
    }
    // Nothing says why: no error_description, no hint at the failed check.
    assertThat(expected)
        .doesNotContainIgnoringCase("expired")
        .doesNotContainIgnoringCase("signature")
        .doesNotContainIgnoringCase("audience")
        .doesNotContainIgnoringCase("issuer");
  }

  @Test
  void anExpiredTokenIsRejectedLikeAnyOther() throws Exception {
    String reference = openAccount("Bearer not-a-jwt").getResponse().getContentAsString();
    // Ten minutes of lifetime plus the 60 seconds of allowed clock skew, and then some.
    clock.advance(Duration.ofMinutes(12));

    MvcTestResult result = openAccount(bearer);

    assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
    assertThat(result).headers().hasValue(HttpHeaders.WWW_AUTHENTICATE, INVALID_TOKEN);
    assertThat(result.getResponse().getContentAsString()).isEqualTo(reference);
  }

  @Test
  void aTokenInTheQueryStringIsIgnored() {
    // Query strings end up in access logs and browser history (RFC 6750 section 5.3).
    String token = bearer.substring("Bearer ".length());

    MvcTestResult result = mvc.post().uri("/accounts?access_token={token}", token).exchange();

    assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
    assertThat(result).headers().hasValue(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
  }

  @Test
  void publicEndpointsIgnoreAStaleToken() {
    // A client that still sends an old token by default must still be able to log in again.
    AuthFixture.LoggedIn user = auth.newCustomer();

    MvcTestResult result =
        mvc.post()
            .uri("/auth/login")
            .header(HttpHeaders.AUTHORIZATION, "Bearer expired-or-garbage")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"username\":\"%s\",\"password\":\"%s\"}"
                    .formatted(user.username(), AuthFixture.PASSWORD))
            .exchange();

    assertThat(result).hasStatus(HttpStatus.OK);
  }

  @Test
  void noSessionIsEverCreated() {
    MvcTestResult result = openAccount(bearer);

    assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    assertThat(result.getRequest().getSession(false)).isNull();
  }

  private MvcTestResult openAccount(String authorization) {
    return mvc.post().uri("/accounts").header(HttpHeaders.AUTHORIZATION, authorization).exchange();
  }

  private String signed(UnaryOperator<JwtClaimsSet.Builder> change, String type) {
    Instant now = clock.instant();
    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .audience(List.of(audience))
            .subject(UUID.randomUUID().toString())
            .claim("roles", List.of("CUSTOMER"))
            .issuedAt(now)
            .expiresAt(now.plus(Duration.ofMinutes(10)));
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type(type).build();
    return appKey
        .encode(JwtEncoderParameters.from(header, change.apply(claims).build()))
        .getTokenValue();
  }

  private String signedWithAnotherKey() {
    KeyPair other = TestJwtKeys.generate(2048);
    JwtEncoder encoder =
        NimbusJwtEncoder.withKeyPair(
                (RSAPublicKey) other.getPublic(), (RSAPrivateKey) other.getPrivate())
            .algorithm(SignatureAlgorithm.RS256)
            .build();
    Instant now = clock.instant();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .audience(List.of(audience))
            .subject(UUID.randomUUID().toString())
            .issuedAt(now)
            .expiresAt(now.plus(Duration.ofMinutes(10)))
            .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type("at+jwt").build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  private static String base64Url(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(String part) {
    return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
  }
}
