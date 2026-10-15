package dev.carlosnobuyuki.bank.domain;

/**
 * The transfer request itself is invalid (non-positive amount, same account, currency mismatch).
 * Unlike a {@link RejectionReason}, this is never persisted and does not consume an idempotency key
 * (ADR-0008).
 */
public class InvalidTransferException extends DomainException {

  private static final long serialVersionUID = 1L;

  public InvalidTransferException(String message) {
    super(message);
  }
}
