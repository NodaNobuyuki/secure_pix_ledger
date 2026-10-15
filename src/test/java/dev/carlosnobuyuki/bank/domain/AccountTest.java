package dev.carlosnobuyuki.bank.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {

  private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

  private static Account customer(long balanceMinor) {
    return Account.restore(
        UUID.randomUUID(),
        AccountType.CUSTOMER,
        AccountStatus.ACTIVE,
        Money.brl(balanceMinor),
        NOW);
  }

  @Test
  void opensActiveWithZeroBalance() {
    Account account = Account.open(UUID.randomUUID(), AccountType.CUSTOMER, Money.BRL, NOW);

    assertThat(account.isActive()).isTrue();
    assertThat(account.balance()).isEqualTo(Money.brl(0));
  }

  @Test
  void creditAndDebitMoveTheBalance() {
    Account account = customer(1000);

    account.credit(Money.brl(500));
    account.debit(Money.brl(1500));

    assertThat(account.balance()).isEqualTo(Money.brl(0));
  }

  @Test
  void customerCannotOverdraw() {
    Account account = customer(1000);

    assertThat(account.canDebit(Money.brl(1001))).isFalse();
    assertThatThrownBy(() -> account.debit(Money.brl(1001)))
        .isInstanceOf(InsufficientFundsException.class);
    assertThat(account.balance()).isEqualTo(Money.brl(1000));
  }

  @Test
  void systemAccountMayGoNegative() {
    Account funding = Account.open(UUID.randomUUID(), AccountType.SYSTEM, Money.BRL, NOW);

    funding.debit(Money.brl(10_000));

    assertThat(funding.balance()).isEqualTo(Money.brl(-10_000));
  }

  @Test
  void rejectsNonPositiveAmounts() {
    Account account = customer(1000);

    assertThatThrownBy(() -> account.credit(Money.brl(0)))
        .isInstanceOf(InvalidTransferException.class);
    assertThatThrownBy(() -> account.debit(Money.brl(-1)))
        .isInstanceOf(InvalidTransferException.class);
  }

  @Test
  void inactiveAccountCannotMoveMoney() {
    Account account =
        Account.restore(
            UUID.randomUUID(), AccountType.CUSTOMER, AccountStatus.INACTIVE, Money.brl(1000), NOW);

    assertThatThrownBy(() -> account.credit(Money.brl(1)))
        .isInstanceOf(InactiveAccountException.class);
    assertThatThrownBy(() -> account.debit(Money.brl(1)))
        .isInstanceOf(InactiveAccountException.class);
  }

  @Test
  void refusesToRestoreANegativeCustomerBalance() {
    assertThatThrownBy(() -> customer(-1)).isInstanceOf(IllegalArgumentException.class);
  }
}
