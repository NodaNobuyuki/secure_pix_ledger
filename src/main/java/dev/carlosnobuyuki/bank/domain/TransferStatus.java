package dev.carlosnobuyuki.bank.domain;

public enum TransferStatus {
  COMPLETED,
  /** Refused by a business rule; stored without ledger entries (ADR-0008). */
  REJECTED
}
