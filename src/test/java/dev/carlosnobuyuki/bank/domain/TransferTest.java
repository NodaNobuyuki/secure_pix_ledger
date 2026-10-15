package dev.carlosnobuyuki.bank.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferTest {

  private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
  private static final String HASH = "test-hash";

  private static Account account(AccountStatus status, long balanceMinor) {
    return Account.restore(
        UUID.randomUUID(), AccountType.CUSTOMER, status, Money.brl(balanceMinor), NOW);
  }

  private static TransferResult execute(Account source, Account destination, Money amount) {
    return Transfer.execute(
        UUID.randomUUID(), UUID.randomUUID(), HASH, source, destination, amount, NOW);
  }

  @Test
  void completedTransferMovesMoneyWithTwoEntriesThatSumToZero() {
    Account source = account(AccountStatus.ACTIVE, 1000);
    Account destination = account(AccountStatus.ACTIVE, 0);

    TransferResult result = execute(source, destination, Money.brl(300));

    Transfer transfer = result.transfer();
    assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(transfer.rejectionReason()).isEmpty();
    assertThat(source.balance()).isEqualTo(Money.brl(700));
    assertThat(destination.balance()).isEqualTo(Money.brl(300));
    assertThat(result.entries())
        .containsExactly(
            new LedgerEntry(transfer.id(), source.id(), Money.brl(-300)),
            new LedgerEntry(transfer.id(), destination.id(), Money.brl(300)));
    assertThat(result.entries().stream().mapToLong(e -> e.amount().amountMinor()).sum()).isZero();
  }

  @Test
  void canSpendTheWholeBalance() {
    Account source = account(AccountStatus.ACTIVE, 1000);

    TransferResult result = execute(source, account(AccountStatus.ACTIVE, 0), Money.brl(1000));

    assertThat(result.transfer().status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(source.balance()).isEqualTo(Money.brl(0));
  }

  @Test
  void insufficientFundsIsRejectedWithoutTouchingBalances() {
    Account source = account(AccountStatus.ACTIVE, 1000);
    Account destination = account(AccountStatus.ACTIVE, 0);

    TransferResult result = execute(source, destination, Money.brl(1001));

    assertThat(result.transfer().status()).isEqualTo(TransferStatus.REJECTED);
    assertThat(result.transfer().rejectionReason()).contains(RejectionReason.INSUFFICIENT_FUNDS);
    assertThat(result.entries()).isEmpty();
    assertThat(source.balance()).isEqualTo(Money.brl(1000));
    assertThat(destination.balance()).isEqualTo(Money.brl(0));
  }

  @Test
  void inactiveSourceIsRejected() {
    TransferResult result =
        execute(
            account(AccountStatus.INACTIVE, 1000), account(AccountStatus.ACTIVE, 0), Money.brl(1));

    assertThat(result.transfer().rejectionReason()).contains(RejectionReason.SOURCE_INACTIVE);
    assertThat(result.entries()).isEmpty();
  }

  @Test
  void inactiveDestinationIsRejected() {
    Account source = account(AccountStatus.ACTIVE, 1000);

    TransferResult result = execute(source, account(AccountStatus.INACTIVE, 0), Money.brl(1));

    assertThat(result.transfer().rejectionReason()).contains(RejectionReason.DESTINATION_INACTIVE);
    assertThat(source.balance()).isEqualTo(Money.brl(1000));
  }

  @Test
  void nonPositiveAmountIsAnInvalidRequest() {
    Account source = account(AccountStatus.ACTIVE, 1000);
    Account destination = account(AccountStatus.ACTIVE, 0);

    assertThatThrownBy(() -> execute(source, destination, Money.brl(0)))
        .isInstanceOf(InvalidTransferException.class);
    assertThatThrownBy(() -> execute(source, destination, Money.brl(-100)))
        .isInstanceOf(InvalidTransferException.class);
  }

  @Test
  void sameAccountIsAnInvalidRequest() {
    Account account = account(AccountStatus.ACTIVE, 1000);

    assertThatThrownBy(() -> execute(account, account, Money.brl(100)))
        .isInstanceOf(InvalidTransferException.class)
        .hasMessageContaining("differ");
    assertThat(account.balance()).isEqualTo(Money.brl(1000));
  }

  @Test
  void currencyMismatchIsAnInvalidRequest() {
    Money usd = new Money(100, Currency.getInstance("USD"));

    assertThatThrownBy(
            () ->
                execute(account(AccountStatus.ACTIVE, 1000), account(AccountStatus.ACTIVE, 0), usd))
        .isInstanceOf(InvalidTransferException.class);
  }

  @Test
  void restoreRequiresAReasonExactlyWhenRejected() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                Transfer.restore(
                    id,
                    UUID.randomUUID(),
                    HASH,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    Money.brl(1),
                    TransferStatus.COMPLETED,
                    RejectionReason.INSUFFICIENT_FUNDS,
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                Transfer.restore(
                    id,
                    UUID.randomUUID(),
                    HASH,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    Money.brl(1),
                    TransferStatus.REJECTED,
                    null,
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
