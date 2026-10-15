package dev.carlosnobuyuki.bank.application;

import dev.carlosnobuyuki.bank.domain.InvalidTransferException;
import dev.carlosnobuyuki.bank.domain.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * A validated request to move money. Construction fails for requests that must not consume the
 * idempotency key (ADR-0008): non-positive amounts and same source and destination.
 */
public record TransferCommand(
    UUID idempotencyKey, UUID sourceAccountId, UUID destinationAccountId, Money amount) {

  public TransferCommand {
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(sourceAccountId, "sourceAccountId");
    Objects.requireNonNull(destinationAccountId, "destinationAccountId");
    Objects.requireNonNull(amount, "amount");
    if (!amount.isPositive()) {
      throw new InvalidTransferException("amount must be positive");
    }
    if (sourceAccountId.equals(destinationAccountId)) {
      throw new InvalidTransferException("source and destination must differ");
    }
  }

  /**
   * Hex SHA-256 of {@code v1|source|destination|amountMinor|currency} (ADR-0008). Built from parsed
   * fields, so JSON formatting never changes it; the version prefix lets the format evolve.
   */
  public String requestHash() {
    String canonical =
        String.join(
            "|",
            "v1",
            sourceAccountId.toString(),
            destinationAccountId.toString(),
            Long.toString(amount.amountMinor()),
            amount.currency().getCurrencyCode());
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // Every Java platform is required to ship SHA-256.
      throw new IllegalStateException(e);
    }
  }
}
