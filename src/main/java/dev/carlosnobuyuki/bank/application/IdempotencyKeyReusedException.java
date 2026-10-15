package dev.carlosnobuyuki.bank.application;

import java.util.UUID;

/** The key was already used for a different request; nothing was executed (ADR-0008). */
public class IdempotencyKeyReusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final UUID idempotencyKey;

  public IdempotencyKeyReusedException(UUID idempotencyKey) {
    super("idempotency key was already used for a different request: " + idempotencyKey);
    this.idempotencyKey = idempotencyKey;
  }

  public UUID idempotencyKey() {
    return idempotencyKey;
  }
}
