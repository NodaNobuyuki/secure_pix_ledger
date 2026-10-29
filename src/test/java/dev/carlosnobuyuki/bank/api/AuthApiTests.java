package dev.carlosnobuyuki.bank.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.carlosnobuyuki.bank.IntegrationTest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
@AutoConfigureMockMvc
class AuthApiTests {

  private static final String PASSWORD = "correct horse battery";

  @Autowired MockMvcTester mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtDecoder decoder;

  @Test
  void registersACustomerWithAnArgon2idHash() throws Exception {
    String username = uniqueUsername();

    MvcTestResult result = register(username, PASSWORD);

    assertThat(result).hasStatus(HttpStatus.CREATED);
    assertThat(result).bodyJson().extractingPath("$.username").isEqualTo(username);
    String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM users WHERE id = ?::uuid", id);
    assertThat(row).containsEntry("username", username).containsEntry("role", "CUSTOMER");
    assertThat((String) row.get("password_hash"))
        .startsWith("{argon2}$argon2id$")
        .doesNotContain(PASSWORD);
  }

  @Test
  void responseNeverContainsThePasswordOrItsHash() throws Exception {
    String body = register(uniqueUsername(), PASSWORD).getResponse().getContentAsString();

    assertThat(body).doesNotContain(PASSWORD, "argon2", "password");
  }

  @Test
  void normalizesTheUsernameToLowercase() {
    String username = uniqueUsername();

    assertThat(register(username.toUpperCase(Locale.ROOT), PASSWORD))
        .hasStatus(HttpStatus.CREATED)
        .bodyJson()
        .extractingPath("$.username")
        .isEqualTo(username);
  }

  @Test
  void takenUsernameIs409RegardlessOfCase() {
    String username = uniqueUsername();
    register(username, PASSWORD);

    MvcTestResult result = register(username.toUpperCase(Locale.ROOT), "another long password");

    assertThat(result)
        .hasStatus(HttpStatus.CONFLICT)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).bodyJson().extractingPath("$.type").asString().endsWith("#username-taken");
  }

  @Test
  void malformedUsernameIs400WithoutEchoingIt() throws Exception {
    MvcTestResult result = register("<script>alert(1)</script>", PASSWORD);

    assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("username");
    assertThat(result.getResponse().getContentAsString()).doesNotContain("script");
  }

  @Test
  void shortPasswordIs400WithoutEchoingIt() throws Exception {
    MvcTestResult result = register(uniqueUsername(), "hunter2");

    assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("password");
    assertThat(result.getResponse().getContentAsString()).doesNotContain("hunter2");
  }

  @Test
  void missingPasswordIs400() {
    MvcTestResult result =
        mvc.post()
            .uri("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + uniqueUsername() + "\"}")
            .exchange();

    assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("password");
  }

  @Test
  void roleInTheRequestIsRejectedNotIgnored() {
    // Mass assignment: a client must not be able to register itself as an admin (ADR-0013).
    String username = uniqueUsername();

    MvcTestResult result =
        mvc.post()
            .uri("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"username\":\"%s\",\"password\":\"%s\",\"role\":\"ADMIN\"}"
                    .formatted(username, PASSWORD))
            .exchange();

    assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE username = ?", Integer.class, username))
        .isZero();
  }

  @Test
  void loginReturnsAnAccessTokenForTheUser() throws Exception {
    String username = uniqueUsername();
    String id =
        JsonPath.read(register(username, PASSWORD).getResponse().getContentAsString(), "$.id");

    MvcTestResult result = login(username.toUpperCase(Locale.ROOT), PASSWORD);

    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().extractingPath("$.tokenType").isEqualTo("Bearer");
    assertThat(result).bodyJson().extractingPath("$.expiresIn").isEqualTo(600);
    String token = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    Jwt jwt = decoder.decode(token);
    assertThat(jwt.getSubject()).isEqualTo(id);
    assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CUSTOMER");
    // Clients may log tokens, so they name the user by id only (ADR-0014).
    assertThat(jwt.getClaims().values().toString()).doesNotContain(username);
  }

  @Test
  void loginResponseIsNeverCached() {
    String username = uniqueUsername();
    register(username, PASSWORD);

    assertThat(login(username, PASSWORD))
        .hasStatus(HttpStatus.OK)
        .headers()
        .hasValue(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  @Test
  void everyLoginFailureGetsTheSameAnswer() throws Exception {
    String username = uniqueUsername();
    register(username, PASSWORD);

    // Wrong password, unknown user, and input that could never have been registered.
    List<MvcTestResult> failures =
        List.of(
            login(username, "wrong horse battery"),
            login(uniqueUsername(), PASSWORD),
            login("<script>", PASSWORD),
            login(username, "short"));

    String expected = failures.getFirst().getResponse().getContentAsString();
    for (MvcTestResult failure : failures) {
      assertThat(failure)
          .hasStatus(HttpStatus.UNAUTHORIZED)
          .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
      assertThat(failure).headers().hasValue(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
      assertThat(failure.getResponse().getContentAsString()).isEqualTo(expected);
    }
    assertThat(failures.getFirst())
        .bodyJson()
        .extractingPath("$.type")
        .asString()
        .endsWith("#invalid-credentials");
    assertThat(expected).doesNotContain(username, "script", "short", "horse");
  }

  @Test
  void loginWithoutAPasswordIs400() {
    MvcTestResult result =
        mvc.post()
            .uri("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + uniqueUsername() + "\"}")
            .exchange();

    assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("password");
  }

  private MvcTestResult login(String username, String password) {
    return mvc.post()
        .uri("/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password))
        .exchange();
  }

  private MvcTestResult register(String username, String password) {
    return mvc.post()
        .uri("/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password))
        .exchange();
  }

  // Tests share one database, so every test registers its own name.
  private static String uniqueUsername() {
    return "user-" + UUID.randomUUID().toString().substring(0, 13);
  }
}
