package dev.carlosnobuyuki.bank.application;

import dev.carlosnobuyuki.bank.domain.Transfer;
import java.util.Objects;

/**
 * The stored transfer for a request, COMPLETED or REJECTED. {@code replayed} is true when the
 * idempotency key had already been used with the same request and nothing new was executed.
 */
public record TransferOutcome(Transfer transfer, boolean replayed) {

  public TransferOutcome {
    Objects.requireNonNull(transfer, "transfer");
  }
}
