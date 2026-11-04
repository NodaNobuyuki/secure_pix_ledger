package dev.carlosnobuyuki.bank.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.carlosnobuyuki.bank.AuthFixture;
import dev.carlosnobuyuki.bank.IntegrationTest;
import dev.carlosnobuyuki.bank.domain.InvalidTransferException;
import dev.carlosnobuyuki.bank.domain.Money;
import dev.carlosnobuyuki.bank.domain.RejectionReason;
import dev.carlosnobuyuki.bank.domain.SystemAccounts;
import dev.carlosnobuyuki.bank.domain.TransferStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Transfer use cases against PostgreSQL. Not {@code @Transactional}: the services must commit for
 * real, so every test uses fresh accounts; {@code @IntegrationTest} checks the ledger after each.
 */
@IntegrationTest
class TransferServiceTests {

  @Autowired TransferService transfers;
  @Autowired DepositService deposits;
  @Autowired AccountService accounts;
  @Autowired AuthFixture auth;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;

  // Every customer account needs an owner (ADR-0015); one per test is enough here.
  private UUID owner;

  @BeforeEach
  void createOwner() {
    owner = auth.newAccountOwner();
  }

  @Test
  void depositIssuesMoneyFromSystemFunding() {
    UUID customer = accounts.open(owner).id();
    long fundingBefore = rawBalance(SystemAccounts.FUNDING_ID);

    TransferOutcome outcome = deposits.deposit(UUID.randomUUID(), customer, Money.brl(10_000));

    assertThat(outcome.replayed()).isFalse();
    assertThat(outcome.transfer().status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(balance(customer)).isEqualTo(10_000);
    assertThat(rawBalance(SystemAccounts.FUNDING_ID)).isEqualTo(fundingBefore - 10_000);
  }

  @Test
  void transfersBetweenCustomers() {
    UUID a = funded(1_000);
    UUID b = accounts.open(owner).id();

    TransferOutcome outcome = transfers.transfer(owner, command(a, b, 300));

    assertThat(outcome.transfer().status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(outcome.replayed()).isFalse();
    assertThat(balance(a)).isEqualTo(700);
    assertThat(balance(b)).isEqualTo(300);
    assertThat(transfers.find(outcome.transfer().id(), owner)).isPresent();
  }

  @Test
  void storesRejectionForInsufficientFundsWithoutMovingMoney() {
    UUID a = funded(100);
    UUID b = accounts.open(owner).id();

    TransferOutcome outcome = transfers.transfer(owner, command(a, b, 101));

    assertThat(outcome.transfer().status()).isEqualTo(TransferStatus.REJECTED);
    assertThat(outcome.transfer().rejectionReason()).contains(RejectionReason.INSUFFICIENT_FUNDS);
    assertThat(balance(a)).isEqualTo(100);
    assertThat(balance(b)).isZero();
    assertThat(entriesOf(outcome.transfer().id())).isZero();
  }

  @Test
  void storesRejectionForInactiveDestination() {
    UUID a = funded(100);
    UUID b = accounts.open(owner).id();
    jdbc.update("UPDATE accounts SET status = 'INACTIVE' WHERE id = ?", b);

    TransferOutcome outcome = transfers.transfer(owner, command(a, b, 50));

    assertThat(outcome.transfer().rejectionReason()).contains(RejectionReason.DESTINATION_INACTIVE);
    assertThat(balance(a)).isEqualTo(100);
  }

  @Test
  void replaysSameRequestWithoutExecutingTwice() {
    UUID a = funded(1_000);
    UUID b = accounts.open(owner).id();
    TransferCommand command = command(a, b, 400);

    TransferOutcome first = transfers.transfer(owner, command);
    TransferOutcome second = transfers.transfer(owner, command);

    assertThat(second.replayed()).isTrue();
    assertThat(second.transfer().id()).isEqualTo(first.transfer().id());
    assertThat(second.transfer().createdAt()).isEqualTo(first.transfer().createdAt());
    assertThat(balance(a)).isEqualTo(600);
    assertThat(transfersWithKey(command.idempotencyKey())).isEqualTo(1);
    assertThat(entriesOf(first.transfer().id())).isEqualTo(2);
  }

  @Test
  void replaysRejectionEvenAfterFundsArrive() {
    UUID a = accounts.open(owner).id();
    UUID b = accounts.open(owner).id();
    TransferCommand command = command(a, b, 500);
    transfers.transfer(owner, command);
    deposits.deposit(UUID.randomUUID(), a, Money.brl(1_000));

    TransferOutcome retry = transfers.transfer(owner, command);

    // The key already maps to a rejection (ADR-0008); a new attempt needs a new key.
    assertThat(retry.replayed()).isTrue();
    assertThat(retry.transfer().status()).isEqualTo(TransferStatus.REJECTED);
    assertThat(balance(a)).isEqualTo(1_000);
  }

  @Test
  void refusesKeyReusedForDifferentRequest() {
    UUID a = funded(1_000);
    UUID b = accounts.open(owner).id();
    UUID key = UUID.randomUUID();
    transfers.transfer(owner, new TransferCommand(key, a, b, Money.brl(100)));

    assertThatThrownBy(
            () -> transfers.transfer(owner, new TransferCommand(key, a, b, Money.brl(200))))
        .isInstanceOf(IdempotencyKeyReusedException.class);
    assertThat(balance(a)).isEqualTo(900);
  }

  @Test
  void unknownAccountDoesNotConsumeKey() {
    UUID a = funded(1_000);
    UUID b = accounts.open(owner).id();
    UUID unknown = UUID.randomUUID();
    UUID key = UUID.randomUUID();

    assertThatThrownBy(
            () -> transfers.transfer(owner, new TransferCommand(key, a, unknown, Money.brl(1))))
        .isInstanceOf(AccountNotFoundException.class)
        .extracting(e -> ((AccountNotFoundException) e).accountId())
        .isEqualTo(unknown);
    assertThat(transfersWithKey(key)).isZero();

    TransferOutcome retry = transfers.transfer(owner, new TransferCommand(key, a, b, Money.brl(1)));
    assertThat(retry.replayed()).isFalse();
  }

  @Test
  void currencyMismatchIsInvalidAndNotStored() {
    UUID a = funded(1_000);
    UUID b = accounts.open(owner).id();
    UUID key = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                transfers.transfer(
                    owner,
                    new TransferCommand(
                        key, a, b, new Money(1, java.util.Currency.getInstance("USD")))))
        .isInstanceOf(InvalidTransferException.class);
    assertThat(transfersWithKey(key)).isZero();
  }

  @Test
  void hidesSystemAccountsFromPublicUseCases() {
    UUID customer = accounts.open(owner).id();
    UUID funding = SystemAccounts.FUNDING_ID;
    UUID depositId = deposits.deposit(UUID.randomUUID(), customer, Money.brl(1)).transfer().id();
    long fundingBefore = rawBalance(funding);

    assertThatThrownBy(() -> transfers.transfer(owner, command(funding, customer, 1_000_000)))
        .isInstanceOf(AccountNotFoundException.class);
    assertThatThrownBy(() -> transfers.transfer(owner, command(customer, funding, 1)))
        .isInstanceOf(AccountNotFoundException.class);
    assertThat(accounts.find(funding, owner)).isEmpty();
    assertThat(transfers.find(depositId, owner)).isEmpty();
    assertThat(rawBalance(funding)).isEqualTo(fundingBefore);
    assertThat(balance(customer)).isEqualTo(1);
  }

  @Test
  void strangerCannotMoveMoneyOutOfSomeoneElsesAccount() {
    UUID victim = funded(1_000);
    UUID stranger = auth.newAccountOwner();
    UUID strangersAccount = accounts.open(stranger).id();
    UUID key = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                transfers.transfer(
                    stranger, new TransferCommand(key, victim, strangersAccount, Money.brl(500))))
        .isInstanceOf(AccountNotFoundException.class)
        .extracting(e -> ((AccountNotFoundException) e).accountId())
        .isEqualTo(victim);
    assertThat(transfersWithKey(key)).isZero();
    assertThat(balance(victim)).isEqualTo(1_000);
  }

  @Test
  void sameKeyFromTwoUsersMakesTwoIndependentTransfers() {
    UUID mine = funded(1_000);
    UUID other = auth.newAccountOwner();
    UUID theirs = accounts.open(other).id();
    deposits.deposit(UUID.randomUUID(), theirs, Money.brl(1_000));
    UUID key = UUID.randomUUID();

    TransferOutcome first =
        transfers.transfer(owner, new TransferCommand(key, mine, theirs, Money.brl(100)));
    // Different body under the same key: a 422 if keys were global, a new transfer per user.
    TransferOutcome second =
        transfers.transfer(other, new TransferCommand(key, theirs, mine, Money.brl(300)));

    assertThat(second.replayed()).isFalse();
    assertThat(second.transfer().id()).isNotEqualTo(first.transfer().id());
    assertThat(first.transfer().initiatedBy()).contains(owner);
    assertThat(second.transfer().initiatedBy()).contains(other);
    assertThat(transfersWithKey(key)).isEqualTo(2);
    assertThat(balance(mine)).isEqualTo(1_200);
  }

  @Test
  void strangerIsRefusedWithoutWaitingForTheVictimsLock() throws Exception {
    UUID victim = funded(1_000);
    UUID stranger = auth.newAccountOwner();
    UUID strangersAccount = accounts.open(stranger).id();

    try (Connection holder = dataSource.getConnection();
        PreparedStatement lock =
            holder.prepareStatement("SELECT 1 FROM accounts WHERE id = ? FOR UPDATE")) {
      holder.setAutoCommit(false);
      lock.setObject(1, victim);
      lock.executeQuery().close();

      long start = System.nanoTime();
      assertThatThrownBy(() -> transfers.transfer(stranger, command(victim, strangersAccount, 1)))
          .isInstanceOf(AccountNotFoundException.class);
      Duration waited = Duration.ofNanos(System.nanoTime() - start);

      // The owner's own transfers wait up to the 3 s lock timeout
      // (failsFastWhenAccountStaysLocked);
      // a stranger's never queue for the lock at all (ADR-0015).
      assertThat(waited).isLessThan(Duration.ofSeconds(1));
      holder.rollback();
    }
  }

  @Test
  void payeeSeesCompletedTransfersButNotRejections() {
    UUID payer = funded(100);
    UUID payee = auth.newAccountOwner();
    UUID payeesAccount = accounts.open(payee).id();

    UUID completed = transfers.transfer(owner, command(payer, payeesAccount, 50)).transfer().id();
    UUID rejected = transfers.transfer(owner, command(payer, payeesAccount, 1_000)).transfer().id();

    assertThat(transfers.find(completed, owner)).isPresent();
    assertThat(transfers.find(rejected, owner)).isPresent();
    assertThat(transfers.find(completed, payee)).isPresent();
    assertThat(transfers.find(rejected, payee)).isEmpty();
    assertThat(transfers.find(completed, auth.newAccountOwner())).isEmpty();
  }

  @Test
  void replaysWinnerWhenLosingTheIdempotencyRace() throws Exception {
    UUID a = funded(1_000);
    UUID b = accounts.open(owner).id();
    TransferCommand command = command(a, b, 250);

    try (Connection winner = dataSource.getConnection()) {
      winner.setAutoCommit(false);
      // The "winner" stores the key first but has not committed: the service's fast path cannot
      // see it, so the service locks, inserts, and blocks on the unique index.
      UUID winnerId = insertRejectedTransfer(winner, owner, command);
      CompletableFuture<TransferOutcome> loser =
          CompletableFuture.supplyAsync(() -> transfers.transfer(owner, command));
      awaitBlockedInsert();

      winner.commit();

      TransferOutcome outcome = loser.get(10, TimeUnit.SECONDS);
      assertThat(outcome.replayed()).isTrue();
      assertThat(outcome.transfer().id()).isEqualTo(winnerId);
    }
    assertThat(transfersWithKey(command.idempotencyKey())).isEqualTo(1);
    assertThat(balance(a)).isEqualTo(1_000);
  }

  @Test
  void failsFastWhenAccountStaysLocked() throws Exception {
    UUID a = funded(1_000);
    UUID b = accounts.open(owner).id();

    try (Connection holder = dataSource.getConnection();
        PreparedStatement lock =
            holder.prepareStatement("SELECT 1 FROM accounts WHERE id = ? FOR UPDATE")) {
      holder.setAutoCommit(false);
      lock.setObject(1, a);
      lock.executeQuery().close();

      long start = System.nanoTime();
      assertThatThrownBy(() -> transfers.transfer(owner, command(a, b, 1)))
          .isInstanceOf(PessimisticLockingFailureException.class);
      Duration waited = Duration.ofNanos(System.nanoTime() - start);

      assertThat(waited).isBetween(Duration.ofMillis(2_500), Duration.ofSeconds(10));
      holder.rollback();
    }
    assertThat(balance(a)).isEqualTo(1_000);
  }

  private static TransferCommand command(UUID source, UUID destination, long amountMinor) {
    return new TransferCommand(UUID.randomUUID(), source, destination, Money.brl(amountMinor));
  }

  private UUID funded(long amountMinor) {
    UUID id = accounts.open(owner).id();
    deposits.deposit(UUID.randomUUID(), id, Money.brl(amountMinor));
    return id;
  }

  private long balance(UUID customer) {
    return accounts.find(customer, owner).orElseThrow().balance().amountMinor();
  }

  private long rawBalance(UUID account) {
    return jdbc.queryForObject(
        "SELECT balance_minor FROM accounts WHERE id = ?", Long.class, account);
  }

  private long entriesOf(UUID transferId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ledger_entries WHERE transfer_id = ?", Long.class, transferId);
  }

  private long transfersWithKey(UUID key) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM transfers WHERE idempotency_key = ?", Long.class, key);
  }

  private static UUID insertRejectedTransfer(
      Connection connection, UUID initiator, TransferCommand command) throws Exception {
    UUID id = UUID.randomUUID();
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO transfers (id, idempotency_key, request_hash, initiated_by,"
                + " source_account_id, destination_account_id, amount_minor, currency, status,"
                + " rejection_reason)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, 'BRL', 'REJECTED', 'INSUFFICIENT_FUNDS')")) {
      insert.setObject(1, id);
      insert.setObject(2, command.idempotencyKey());
      insert.setString(3, command.requestHash());
      insert.setObject(4, initiator);
      insert.setObject(5, command.sourceAccountId());
      insert.setObject(6, command.destinationAccountId());
      insert.setLong(7, command.amount().amountMinor());
      insert.executeUpdate();
    }
    return id;
  }

  // Waits until some session is blocked on a lock while inserting a transfer.
  private void awaitBlockedInsert() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      Long blocked =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity"
                  + " WHERE wait_event_type = 'Lock' AND query ILIKE 'insert into transfers%'",
              Long.class);
      if (blocked != null && blocked > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("the service never blocked on the idempotency key");
  }
}
