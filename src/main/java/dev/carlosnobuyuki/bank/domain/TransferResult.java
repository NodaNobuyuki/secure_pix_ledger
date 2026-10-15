package dev.carlosnobuyuki.bank.domain;

import java.util.List;
import java.util.Objects;

/** A transfer plus the entries to append: two for COMPLETED, none for REJECTED. */
public record TransferResult(Transfer transfer, List<LedgerEntry> entries) {

  public TransferResult {
    Objects.requireNonNull(transfer, "transfer");
    entries = List.copyOf(entries);
  }
}
