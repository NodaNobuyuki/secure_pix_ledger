package dev.carlosnobuyuki.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Real ports, unlike MockMvc: Boot installs the security filter on the management port too, so this
 * proves the healthcheck and Prometheus still get in without a token (ADR-0005, ADR-0014).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
// Tests turn metrics exporters off by default, which would remove the Prometheus endpoint.
@AutoConfigureMetrics
@Import(TestcontainersConfiguration.class)
class ManagementPortTests {

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int apiPort;
  @LocalManagementPort int managementPort;

  @Test
  void healthAndMetricsNeedNoToken() throws Exception {
    assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(200);
    assertThat(get(managementPort, "/actuator/prometheus").statusCode()).isEqualTo(200);
  }

  @Test
  void theApiPortStillDemandsATokenAndAnswersWithAProblem() throws Exception {
    HttpResponse<String> response = get(apiPort, "/accounts/" + UUID.randomUUID());

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
  }

  @Test
  void theActuatorExceptionOnlyAppliesOnTheManagementPort() throws Exception {
    // On the API port the actuator chain does not match, so the main chain asks for a token.
    assertThat(get(apiPort, "/actuator/health").statusCode()).isEqualTo(401);
  }

  private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
