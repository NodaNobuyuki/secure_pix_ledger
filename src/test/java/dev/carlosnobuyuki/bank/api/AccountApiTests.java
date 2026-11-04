package dev.carlosnobuyuki.bank.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.carlosnobuyuki.bank.AuthFixture;
import dev.carlosnobuyuki.bank.IntegrationTest;
import dev.carlosnobuyuki.bank.domain.SystemAccounts;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
@AutoConfigureMockMvc
class AccountApiTests {

  @Autowired MockMvcTester mvc;
  @Autowired AuthFixture auth;

  // Value of the Authorization header; every endpoint here needs a logged-in user (ADR-0014).
  private String bearer;

  @BeforeEach
  void logIn() {
    bearer = auth.newCustomer().authorization();
  }

  @Test
  void opensAccountWithZeroBalance() {
    MvcTestResult result =
        mvc.post().uri("/accounts").header(HttpHeaders.AUTHORIZATION, bearer).exchange();

    assertThat(result).hasStatus(HttpStatus.CREATED);
    String id = result.getResponse().getHeader("Location").replace("/accounts/", "");
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(id);
    assertThat(result).bodyJson().extractingPath("$.balanceMinor").isEqualTo(0);
    assertThat(result).bodyJson().extractingPath("$.currency").isEqualTo("BRL");
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
  }

  @Test
  void getsAccount() {
    String location =
        mvc.post()
            .uri("/accounts")
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .exchange()
            .getResponse()
            .getHeader("Location");

    assertThat(mvc.get().uri(location).header(HttpHeaders.AUTHORIZATION, bearer))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.balanceMinor")
        .isEqualTo(0);
  }

  @Test
  void unknownAccountIsProblem404() {
    MvcTestResult result =
        mvc.get()
            .uri("/accounts/{id}", UUID.randomUUID())
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .exchange();

    assertThat(result)
        .hasStatus(HttpStatus.NOT_FOUND)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.type")
        .asString()
        .endsWith("#account-not-found");
  }

  @Test
  void systemAccountLooksExactlyLikeUnknownAccount() throws Exception {
    UUID unknownId = UUID.randomUUID();
    String unknown =
        mvc.get()
            .uri("/accounts/{id}", unknownId)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .exchange()
            .getResponse()
            .getContentAsString();
    String system =
        mvc.get()
            .uri("/accounts/{id}", SystemAccounts.FUNDING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .exchange()
            .getResponse()
            .getContentAsString();

    // Identical apart from the requested id in "instance" (ADR-0010).
    assertThat(system.replace(SystemAccounts.FUNDING_ID.toString(), "ID"))
        .isEqualTo(unknown.replace(unknownId.toString(), "ID"));
  }

  @Test
  void someoneElsesAccountLooksExactlyLikeUnknownAccount() throws Exception {
    String alicesAccount = openAccount(bearer);
    String bob = auth.newCustomer().authorization();
    UUID unknownId = UUID.randomUUID();

    MvcTestResult others =
        mvc.get()
            .uri("/accounts/{id}", alicesAccount)
            .header(HttpHeaders.AUTHORIZATION, bob)
            .exchange();
    String unknown =
        mvc.get()
            .uri("/accounts/{id}", unknownId)
            .header(HttpHeaders.AUTHORIZATION, bob)
            .exchange()
            .getResponse()
            .getContentAsString();

    // A 403 would confirm the id exists (ADR-0015).
    assertThat(others).hasStatus(HttpStatus.NOT_FOUND);
    assertThat(others.getResponse().getContentAsString().replace(alicesAccount, "ID"))
        .isEqualTo(unknown.replace(unknownId.toString(), "ID"));
  }

  @Test
  void listsOnlyTheCallersAccountsOldestFirst() {
    String first = openAccount(bearer);
    String second = openAccount(bearer);
    openAccount(auth.newCustomer().authorization());

    MvcTestResult result =
        mvc.get().uri("/accounts").header(HttpHeaders.AUTHORIZATION, bearer).exchange();

    assertThat(result).hasStatusOk();
    assertThat(result)
        .bodyJson()
        .extractingPath("$.accounts[*].id")
        .asArray()
        .containsExactly(first, second);
  }

  @Test
  void newUserHasNoAccounts() {
    assertThat(mvc.get().uri("/accounts").header(HttpHeaders.AUTHORIZATION, bearer))
        .hasStatusOk()
        .bodyJson()
        .isLenientlyEqualTo("{\"accounts\":[]}");
  }

  @Test
  void malformedIdIs400WithoutEchoingInput() {
    MvcTestResult result =
        mvc.get()
            .uri("/accounts/not-a-uuid<script>")
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .exchange();

    assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.detail")
        .asString()
        .doesNotContain("script", "java.");
  }

  /** Opens an account as the given user and returns its id. */
  private String openAccount(String authorization) {
    return mvc.post()
        .uri("/accounts")
        .header(HttpHeaders.AUTHORIZATION, authorization)
        .exchange()
        .getResponse()
        .getHeader("Location")
        .replace("/accounts/", "");
  }
}
