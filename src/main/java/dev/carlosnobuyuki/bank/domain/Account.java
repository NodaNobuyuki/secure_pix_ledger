package dev.carlosnobuyuki.bank.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An account and its materialized balance (ADR-0006).
 *
 * <p>The balance only changes through {@link #debit} and {@link #credit}, which {@link
 * Transfer#execute} calls in pairs. Callers are expected to hold the row lock (ADR-0007).
 *
 * <p>Customer accounts have exactly one owner; internal (SYSTEM) accounts have none (ADR-0015).
 */
public final class Account {

  private final UUID id;
  private final AccountType type;
  private final UUID ownerId;
  private final AccountStatus status;
  private final Instant createdAt;
  private Money balance;

  private Account(
      UUID id,
      AccountType type,
      UUID ownerId,
      AccountStatus status,
      Money balance,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.type = Objects.requireNonNull(type, "type");
    this.ownerId = ownerId;
    this.status = Objects.requireNonNull(status, "status");
    this.balance = Objects.requireNonNull(balance, "balance");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if (type == AccountType.CUSTOMER && balance.isNegative()) {
      throw new IllegalArgumentException("customer account " + id + " has a negative balance");
    }
    // Same rule as the accounts_owner_iff_customer constraint.
    if ((type == AccountType.CUSTOMER) != (ownerId != null)) {
      throw new IllegalArgumentException(
          type
              + " account "
              + id
              + (ownerId == null ? " needs an owner" : " cannot have an owner"));
    }
  }

  /**
   * Opens a customer account at zero; money only arrives through transfers. Internal accounts are
   * never opened by code: migrations create them (ADR-0009).
   */
  public static Account open(UUID id, UUID ownerId, Currency currency, Instant now) {
    Objects.requireNonNull(ownerId, "ownerId");
    return new Account(
        id, AccountType.CUSTOMER, ownerId, AccountStatus.ACTIVE, Money.zero(currency), now);
  }

  /**
   * Rebuilds an account from storage. Rejects states the database should never contain.
   *
   * @param ownerId the owner, or {@code null} for an internal account
   */
  public static Account restore(
      UUID id,
      AccountType type,
      UUID ownerId,
      AccountStatus status,
      Money balance,
      Instant createdAt) {
    return new Account(id, type, ownerId, status, balance, createdAt);
  }

  /** False for internal accounts, which belong to no user (ADR-0010, ADR-0015). */
  public boolean isOwnedBy(UUID userId) {
    return ownerId != null && ownerId.equals(userId);
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

  /** Empty for internal accounts. */
  public Optional<UUID> ownerId() {
    return Optional.ofNullable(ownerId);
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
