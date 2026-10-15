package dev.carlosnobuyuki.bank.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * An account and its materialized balance (ADR-0006).
 *
 * <p>The balance only changes through {@link #debit} and {@link #credit}, which {@link
 * Transfer#execute} calls in pairs. Callers are expected to hold the row lock (ADR-0007).
 */
public final class Account {

  private final UUID id;
  private final AccountType type;
  private final AccountStatus status;
  private final Instant createdAt;
  private Money balance;

  private Account(
      UUID id, AccountType type, AccountStatus status, Money balance, Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.type = Objects.requireNonNull(type, "type");
    this.status = Objects.requireNonNull(status, "status");
    this.balance = Objects.requireNonNull(balance, "balance");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if (type == AccountType.CUSTOMER && balance.isNegative()) {
      throw new IllegalArgumentException("customer account " + id + " has a negative balance");
    }
  }

  /** A new account always starts at zero; money only arrives through transfers. */
  public static Account open(UUID id, AccountType type, Currency currency, Instant now) {
    return new Account(id, type, AccountStatus.ACTIVE, Money.zero(currency), now);
  }

  /** Rebuilds an account from storage. Rejects states the database should never contain. */
  public static Account restore(
      UUID id, AccountType type, AccountStatus status, Money balance, Instant createdAt) {
    return new Account(id, type, status, balance, createdAt);
  }

  public boolean isActive() {
    return status == AccountStatus.ACTIVE;
  }

  public boolean canDebit(Money amount) {
    return type == AccountType.SYSTEM || !balance.minus(amount).isNegative();
  }

  public void debit(Money amount) {
    requireUsable(amount);
    if (!canDebit(amount)) {
      throw new InsufficientFundsException(id);
    }
    balance = balance.minus(amount);
  }

  public void credit(Money amount) {
    requireUsable(amount);
    balance = balance.plus(amount);
  }

  // Transfer checks these first and turns them into REJECTED outcomes; repeating them here keeps
  // the account safe even if a future caller forgets.
  private void requireUsable(Money amount) {
    Objects.requireNonNull(amount, "amount");
    if (!amount.isPositive()) {
      throw new InvalidTransferException("amount must be positive");
    }
    if (!isActive()) {
      throw new InactiveAccountException(id);
    }
  }

  public UUID id() {
    return id;
  }

  public AccountType type() {
    return type;
  }

  public AccountStatus status() {
    return status;
  }

  public Money balance() {
    return balance;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
