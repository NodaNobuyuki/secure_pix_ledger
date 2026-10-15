package dev.carlosnobuyuki.bank.application;

import java.util.UUID;

/**
 * The account does not exist, or is an internal account that public use cases must not reveal
 * (ADR-0010). Not persisted; does not consume an idempotency key.
 */
public class AccountNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final UUID accountId;

  public AccountNotFoundException(UUID accountId) {
    super("account not found: " + accountId);
    this.accountId = accountId;
  }

  public UUID accountId() {
    return accountId;
  }
}
