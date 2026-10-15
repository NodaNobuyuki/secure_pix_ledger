package dev.carlosnobuyuki.bank.api;

import dev.carlosnobuyuki.bank.application.TransferOutcome;
import dev.carlosnobuyuki.bank.application.TransferService;
import dev.carlosnobuyuki.bank.domain.Transfer;
import dev.carlosnobuyuki.bank.domain.TransferStatus;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/transfers")
class TransferController {

  static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

  private final TransferService transfers;

  TransferController(TransferService transfers) {
    this.transfers = transfers;
  }

  /**
   * Creates a transfer at most once per key (ADR-0008). A replay returns the original status and
   * body, plus {@code Idempotent-Replayed: true}.
   */
  @PostMapping
  ResponseEntity<Object> create(
      @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
      @Valid @RequestBody TransferRequest request) {
    TransferOutcome outcome = transfers.transfer(request.toCommand(idempotencyKey));
    Transfer transfer = outcome.transfer();

    ResponseEntity.BodyBuilder response;
    Object body;
    if (transfer.status() == TransferStatus.COMPLETED) {
      response = ResponseEntity.created(location(transfer));
      body = TransferResponse.from(transfer);
    } else {
      response = ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT);
      body = rejection(transfer);
    }
    if (outcome.replayed()) {
      response.header(IDEMPOTENT_REPLAYED, "true");
    }
    return response.body(body);
  }

  @GetMapping("/{id}")
  TransferResponse get(@PathVariable UUID id) {
    return transfers
        .find(id)
        .map(TransferResponse::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  // A rejection is stored and has an id (ADR-0008), so the problem points to it.
  private static ProblemDetail rejection(Transfer transfer) {
    var reason = transfer.rejectionReason().orElseThrow();
    ProblemDetail problem =
        Problems.of(
            HttpStatus.UNPROCESSABLE_CONTENT,
            Problems.TRANSFER_REJECTED,
            "Transfer rejected",
            switch (reason) {
              case INSUFFICIENT_FUNDS -> "The source account has insufficient funds.";
              case SOURCE_INACTIVE -> "The source account is inactive.";
              case DESTINATION_INACTIVE -> "The destination account is inactive.";
            });
    problem.setProperty("reason", reason);
    problem.setProperty("transferId", transfer.id());
    problem.setInstance(location(transfer));
    return problem;
  }

  private static URI location(Transfer transfer) {
    return URI.create("/transfers/" + transfer.id());
  }
}
