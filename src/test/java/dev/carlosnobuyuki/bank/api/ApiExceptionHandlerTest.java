package dev.carlosnobuyuki.bank.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;

// No URL rule can produce a 403 until roles are enforced (ADR-0015), so the mapping is tested here.
class ApiExceptionHandlerTest {

  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  void accessDeniedIs403WithAFixedDetail() {
    ProblemDetail problem = handler.forbidden(new AccessDeniedException("needs ROLE_ADMIN"));

    assertThat(problem.getStatus()).isEqualTo(403);
    assertThat(problem.getDetail())
        .isEqualTo("You do not have access to this resource.")
        .doesNotContain("ROLE_ADMIN");
  }
}
