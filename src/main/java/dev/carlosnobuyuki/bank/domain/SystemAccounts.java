package dev.carlosnobuyuki.bank.domain;

import java.util.UUID;

/** Well-known internal accounts created by database migrations (ADR-0009). */
public final class SystemAccounts {

  /** Source of all money in the system; its negated balance is the total issued (ADR-0006). */
  public static final UUID FUNDING_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private SystemAccounts() {}
}
