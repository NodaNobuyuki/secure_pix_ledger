package dev.carlosnobuyuki.bank.api;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Problem types this API defines, documented in docs/problems.md (ADR-0011). */
final class Problems {

  // RFC 9457 asks for absolute type URIs; these resolve to the documentation of each type.
  private static final String BASE =
      "https://github.com/NodaNobuyuki/secure_pix_ledger/blob/main/docs/problems.md#";

  static final URI ACCOUNT_NOT_FOUND = URI.create(BASE + "account-not-found");
  static final URI TRANSFER_REJECTED = URI.create(BASE + "transfer-rejected");
  static final URI IDEMPOTENCY_KEY_REUSED = URI.create(BASE + "idempotency-key-reused");
  static final URI INVALID_TRANSFER = URI.create(BASE + "invalid-transfer");
  static final URI ACCOUNT_BUSY = URI.create(BASE + "account-busy");
  static final URI USERNAME_TAKEN = URI.create(BASE + "username-taken");
  static final URI INVALID_CREDENTIALS = URI.create(BASE + "invalid-credentials");

  private Problems() {}

  static ProblemDetail of(HttpStatus status, URI type, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(type);
    problem.setTitle(title);
    return problem;
  }
}
