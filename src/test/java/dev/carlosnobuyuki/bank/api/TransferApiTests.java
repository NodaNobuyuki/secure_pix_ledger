package dev.carlosnobuyuki.bank.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.carlosnobuyuki.bank.IntegrationTest;
import dev.carlosnobuyuki.bank.application.AccountService;
import dev.carlosnobuyuki.bank.application.DepositService;
import dev.carlosnobuyuki.bank.domain.Money;
import dev.carlosnobuyuki.bank.domain.SystemAccounts;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
@AutoConfigureMockMvc
class TransferApiTests {

  @Autowired MockMvcTester mvc;
  @Autowired AccountService accounts;
  @Autowired DepositService deposits;
  @Autowired DataSource dataSource;

  @Test
  void completedTransferIs201WithLocation() {
    UUID a = funded(1_000);
    UUID b = accounts.open().id();

    MvcTestResult result = post(UUID.randomUUID(), body(a, b, 300));

    assertThat(result).hasStatus(HttpStatus.CREATED).doesNotContainHeader("Idempotent-Replayed");
    String id = result.getResponse().getHeader("Location").replace("/transfers/", "");
    assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(id);
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");
    assertThat(result).bodyJson().extractingPath("$.amountMinor").isEqualTo(300);
    assertThat(mvc.get().uri("/transfers/{id}", id)).hasStatusOk();
    assertThat(mvc.get().uri("/accounts/{id}", a))
        .bodyJson()
        .extractingPath("$.balanceMinor")
        .isEqualTo(700);
  }

  @Test
  void replayReturnsSameStatusAndBodyWithHeader() throws Exception {
    UUID a = funded(1_000);
    UUID b = accounts.open().id();
    UUID key = UUID.randomUUID();

    MvcTestResult first = post(key, body(a, b, 300));
    MvcTestResult second = post(key, body(a, b, 300));

    assertThat(second).hasStatus(HttpStatus.CREATED).hasHeader("Idempotent-Replayed", "true");
    assertThat(second.getResponse().getContentAsString())
        .isEqualTo(first.getResponse().getContentAsString());
    assertThat(mvc.get().uri("/accounts/{id}", a))
        .bodyJson()
        .extractingPath("$.balanceMinor")
        .isEqualTo(700);
  }

  @Test
  void rejectionIs422ProblemAndReplaysTheSame() throws Exception {
    UUID a = accounts.open().id();
    UUID b = accounts.open().id();
    UUID key = UUID.randomUUID();

    MvcTestResult first = post(key, body(a, b, 1));
    MvcTestResult replay = post(key, body(a, b, 1));

    assertThat(first)
        .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(first).bodyJson().extractingPath("$.type").asString().endsWith("#transfer-rejected");
    assertThat(first).bodyJson().extractingPath("$.reason").isEqualTo("INSUFFICIENT_FUNDS");
    String transferId =
        (String)
            com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.transferId");
    assertThat(mvc.get().uri("/transfers/{id}", transferId))
        .bodyJson()
        .extractingPath("$.status")
        .isEqualTo("REJECTED");

    assertThat(replay)
        .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        .hasHeader("Idempotent-Replayed", "true");
    assertThat(replay.getResponse().getContentAsString())
        .isEqualTo(first.getResponse().getContentAsString());
  }

  @Test
  void keyReusedWithDifferentBodyIs422() {
    UUID a = funded(1_000);
    UUID b = accounts.open().id();
    UUID key = UUID.randomUUID();
    post(key, body(a, b, 100));

    MvcTestResult result = post(key, body(a, b, 200));

    assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.type")
        .asString()
        .endsWith("#idempotency-key-reused");
  }

  @Test
  void missingIdempotencyKeyIs400() {
    UUID a = funded(1_000);
    UUID b = accounts.open().id();

    assertThat(
            mvc.post()
                .uri("/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(a, b, 1)))
        .hasStatus(HttpStatus.BAD_REQUEST);
  }

  @Test
  void malformedIdempotencyKeyIs400WithoutEchoingIt() {
    MvcTestResult result =
        mvc.post()
            .uri("/transfers")
            .header("Idempotency-Key", "not-a-uuid<script>")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(UUID.randomUUID(), UUID.randomUUID(), 1))
            .exchange();

    assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.detail")
        .asString()
        .doesNotContain("script", "java.");
  }

  @Test
  void invalidFieldsAre400WithFieldList() {
    MvcTestResult result =
        post(
            UUID.randomUUID(),
            """
            {"sourceAccountId": "%s", "amountMinor": 0, "currency": "USD"}
            """
                .formatted(UUID.randomUUID()));

    assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.errors[*].field")
        .asArray()
        .containsExactlyInAnyOrder("destinationAccountId", "amountMinor", "currency");
  }

  @Test
  void rejectsLossyOrUnknownJson() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    String fractional =
        """
        {"sourceAccountId": "%s", "destinationAccountId": "%s", "amountMinor": 1.5, "currency": "BRL"}
        """
            .formatted(a, b);
    String quoted =
        """
        {"sourceAccountId": "%s", "destinationAccountId": "%s", "amountMinor": "100", "currency": "BRL"}
        """
            .formatted(a, b);
    String unknownField =
        """
        {"sourceAccountId": "%s", "destinationAccountId": "%s", "amountMinor": 1, "currency": "BRL",
         "status": "COMPLETED"}
        """
            .formatted(a, b);

    assertThat(post(UUID.randomUUID(), fractional)).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(post(UUID.randomUUID(), quoted)).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(post(UUID.randomUUID(), unknownField)).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(post(UUID.randomUUID(), "{not json")).hasStatus(HttpStatus.BAD_REQUEST);
  }

  @Test
  void sameAccountIs422InvalidTransfer() {
    UUID a = funded(1_000);

    MvcTestResult result = post(UUID.randomUUID(), body(a, a, 1));

    assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(result).bodyJson().extractingPath("$.type").asString().endsWith("#invalid-transfer");
  }

  @Test
  void unknownOrSystemAccountIs404() {
    UUID a = funded(1_000);

    assertThat(post(UUID.randomUUID(), body(a, UUID.randomUUID(), 1)))
        .hasStatus(HttpStatus.NOT_FOUND);
    assertThat(post(UUID.randomUUID(), body(SystemAccounts.FUNDING_ID, a, 1_000_000)))
        .hasStatus(HttpStatus.NOT_FOUND);
  }

  @Test
  void unknownTransferIs404() {
    assertThat(mvc.get().uri("/transfers/{id}", UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
  }

  @Test
  void lockTimeoutIs503WithRetryAfter() throws Exception {
    UUID a = funded(1_000);
    UUID b = accounts.open().id();

    try (Connection holder = dataSource.getConnection();
        PreparedStatement lock =
            holder.prepareStatement("SELECT 1 FROM accounts WHERE id = ? FOR UPDATE")) {
      holder.setAutoCommit(false);
      lock.setObject(1, a);
      lock.executeQuery().close();

      MvcTestResult result = post(UUID.randomUUID(), body(a, b, 1));

      assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE).hasHeader("Retry-After", "1");
      assertThat(result).bodyJson().extractingPath("$.type").asString().endsWith("#account-busy");
      holder.rollback();
    }
  }

  private MvcTestResult post(UUID idempotencyKey, String json) {
    return mvc.post()
        .uri("/transfers")
        .header("Idempotency-Key", idempotencyKey.toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(json)
        .exchange();
  }

  private static String body(UUID source, UUID destination, long amountMinor) {
    return """
        {"sourceAccountId": "%s", "destinationAccountId": "%s", "amountMinor": %d, "currency": "BRL"}
        """
        .formatted(source, destination, amountMinor);
  }

  private UUID funded(long amountMinor) {
    UUID id = accounts.open().id();
    deposits.deposit(UUID.randomUUID(), id, Money.brl(amountMinor));
    return id;
  }
}
