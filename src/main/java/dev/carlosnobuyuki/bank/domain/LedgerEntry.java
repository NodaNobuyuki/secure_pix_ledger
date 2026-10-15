package dev.carlosnobuyuki.bank.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One side of a completed transfer: negative for the debit, positive for the credit. Entries are
 * append-only (ADR-0006); they have no identity in the domain, the database assigns one.
 */
public record LedgerEntry(UUID transferId, UUID accountId, Money amount) {

  public LedgerEntry {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(amount, "amount");
    if (amount.isZero()) {
      throw new IllegalArgumentException("ledger entry amount must not be zero");
    }
  }
}
