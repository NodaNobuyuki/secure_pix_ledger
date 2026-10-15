package dev.carlosnobuyuki.bank.api;

import dev.carlosnobuyuki.bank.domain.Account;
import dev.carlosnobuyuki.bank.domain.AccountStatus;
import java.time.Instant;
import java.util.UUID;

/** Money is always minor units plus an ISO 4217 code (ADR-0006). */
record AccountResponse(
    UUID id, AccountStatus status, long balanceMinor, String currency, Instant createdAt) {

  static AccountResponse from(Account account) {
    return new AccountResponse(
        account.id(),
        account.status(),
        account.balance().amountMinor(),
        account.balance().currency().getCurrencyCode(),
        account.createdAt());
  }
}
