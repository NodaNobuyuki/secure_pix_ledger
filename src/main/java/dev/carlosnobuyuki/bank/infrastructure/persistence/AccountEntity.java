package dev.carlosnobuyuki.bank.infrastructure.persistence;

import dev.carlosnobuyuki.bank.domain.Account;
import dev.carlosnobuyuki.bank.domain.AccountStatus;
import dev.carlosnobuyuki.bank.domain.AccountType;
import dev.carlosnobuyuki.bank.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "accounts")
class AccountEntity {

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private AccountType type;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AccountStatus status;

  // The column is CHAR(3); without this Hibernate expects VARCHAR and schema validation fails.
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, updatable = false, length = 3)
  private String currency;

  @Column(name = "balance_minor", nullable = false)
  private long balanceMinor;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AccountEntity() {
    // for JPA
  }

  static AccountEntity from(Account account) {
    AccountEntity entity = new AccountEntity();
    entity.id = account.id();
    entity.type = account.type();
    entity.status = account.status();
    entity.currency = account.balance().currency().getCurrencyCode();
    entity.balanceMinor = account.balance().amountMinor();
    entity.createdAt = account.createdAt();
    return entity;
  }

  Account toDomain() {
    return Account.restore(
        id, type, status, new Money(balanceMinor, Currency.getInstance(currency)), createdAt);
  }

  UUID id() {
    return id;
  }

  void setBalance(Money balance) {
    if (!balance.currency().getCurrencyCode().equals(currency)) {
      throw new IllegalArgumentException("balance currency does not match account " + id);
    }
    this.balanceMinor = balance.amountMinor();
  }
}
