package dev.carlosnobuyuki.bank.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.carlosnobuyuki.bank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTests {

  @Autowired MockMvcTester mvc;

  @Test
  void servesOpenApiSpec() {
    assertThat(mvc.get().uri("/v3/api-docs"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.openapi")
        .asString()
        .startsWith("3.");
  }

  @Test
  void servesSwaggerUi() {
    // springdoc redirects the configured path to the bundled UI's index page.
    assertThat(mvc.get().uri("/swagger-ui")).hasStatus3xxRedirection();
  }
}
