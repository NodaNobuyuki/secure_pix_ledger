package dev.carlosnobuyuki.bank.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.carlosnobuyuki.bank.IntegrationTest;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
@AutoConfigureMockMvc
class AuthApiTests {

  private static final String PASSWORD = "correct horse battery";

  @Autowired MockMvcTester mvc;
  @Autowired JdbcTemplate jdbc;

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
