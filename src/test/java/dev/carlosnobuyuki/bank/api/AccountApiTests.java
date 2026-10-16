package dev.carlosnobuyuki.bank.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.carlosnobuyuki.bank.IntegrationTest;
import dev.carlosnobuyuki.bank.domain.SystemAccounts;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
@AutoConfigureMockMvc
class AccountApiTests {

  @Autowired MockMvcTester mvc;

  @Test
  void opensAccountWithZeroBalance() {
    MvcTestResult result = mvc.post().uri("/accounts").exchange();

    assertThat(result).hasStatus(HttpStatus.CREATED);
    String id = result.getResponse().getHeader("Location").replace("/accounts/", "");
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(id);
    assertThat(result).bodyJson().extractingPath("$.balanceMinor").isEqualTo(0);
    assertThat(result).bodyJson().extractingPath("$.currency").isEqualTo("BRL");
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
  }

  @Test
  void getsAccount() {
    String location = mvc.post().uri("/accounts").exchange().getResponse().getHeader("Location");

    assertThat(mvc.get().uri(location))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.balanceMinor")
        .isEqualTo(0);
  }

  @Test
  void unknownAccountIsProblem404() {
    MvcTestResult result = mvc.get().uri("/accounts/{id}", UUID.randomUUID()).exchange();

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
        mvc.get().uri("/accounts/{id}", unknownId).exchange().getResponse().getContentAsString();
    String system =
        mvc.get()
            .uri("/accounts/{id}", SystemAccounts.FUNDING_ID)
            .exchange()
            .getResponse()
            .getContentAsString();

    // Identical apart from the requested id in "instance" (ADR-0010).
    assertThat(system.replace(SystemAccounts.FUNDING_ID.toString(), "ID"))
        .isEqualTo(unknown.replace(unknownId.toString(), "ID"));
  }

  @Test
  void malformedIdIs400WithoutEchoingInput() {
    MvcTestResult result = mvc.get().uri("/accounts/not-a-uuid<script>").exchange();

    assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.detail")
        .asString()
        .doesNotContain("script", "java.");
  }
}
