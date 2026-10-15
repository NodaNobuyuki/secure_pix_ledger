package dev.carlosnobuyuki.bank.api;

import dev.carlosnobuyuki.bank.domain.RejectionReason;
import dev.carlosnobuyuki.bank.domain.Transfer;
import dev.carlosnobuyuki.bank.domain.TransferStatus;
import java.time.Instant;
import java.util.UUID;

record TransferResponse(
    UUID id,
    UUID sourceAccountId,
    UUID destinationAccountId,
    long amountMinor,
    String currency,
    TransferStatus status,
    RejectionReason rejectionReason,
    Instant createdAt) {

  static TransferResponse from(Transfer transfer) {
    return new TransferResponse(
        transfer.id(),
        transfer.sourceAccountId(),
        transfer.destinationAccountId(),
        transfer.amount().amountMinor(),
        transfer.amount().currency().getCurrencyCode(),
        transfer.status(),
        transfer.rejectionReason().orElse(null),
        transfer.createdAt());
  }
}
