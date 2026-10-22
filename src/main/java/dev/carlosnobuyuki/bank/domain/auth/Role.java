package dev.carlosnobuyuki.bank.domain.auth;

/** What a user may do. Self-registration only ever creates a {@link #CUSTOMER} (ADR-0013). */
public enum Role {
  /** Owns accounts and moves money between them. */
  CUSTOMER,
  /** Support staff: reads customers' accounts and changes their status, never moves money. */
  ADMIN
}
