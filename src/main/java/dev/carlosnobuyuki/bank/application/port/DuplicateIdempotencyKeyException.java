package dev.carlosnobuyuki.bank.application.port;

import java.util.UUID;

/**
 * Another transaction stored a transfer with the same idempotency key first. The caller should roll
 * back and replay the stored transfer (ADR-0008).
 */
public class DuplicateIdempotencyKeyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final UUID idempotencyKey;

  public DuplicateIdempotencyKeyException(UUID idempotencyKey, Throwable cause) {
    super("idempotency key already used: " + idempotencyKey, cause);
    this.idempotencyKey = idempotencyKey;
  }

  public UUID idempotencyKey() {
    return idempotencyKey;
  }
}
