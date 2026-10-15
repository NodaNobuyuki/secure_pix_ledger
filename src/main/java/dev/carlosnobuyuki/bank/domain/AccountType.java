package dev.carlosnobuyuki.bank.domain;

public enum AccountType {
  /** Belongs to a customer; can never go negative. */
  CUSTOMER,
  /** Internal bookkeeping account such as SYSTEM_FUNDING; may go negative (ADR-0006). */
  SYSTEM
}
