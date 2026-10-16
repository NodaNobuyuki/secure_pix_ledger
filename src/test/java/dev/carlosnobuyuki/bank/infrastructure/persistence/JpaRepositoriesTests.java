package dev.carlosnobuyuki.bank.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.carlosnobuyuki.bank.IntegrationTest;
import dev.carlosnobuyuki.bank.application.port.AccountRepository;
import dev.carlosnobuyuki.bank.application.port.DuplicateIdempotencyKeyException;
import dev.carlosnobuyuki.bank.application.port.TransferRepository;
import dev.carlosnobuyuki.bank.domain.Account;
import dev.carlosnobuyuki.bank.domain.AccountType;
import dev.carlosnobuyuki.bank.domain.Money;
import dev.carlosnobuyuki.bank.domain.RejectionReason;
import dev.carlosnobuyuki.bank.domain.SystemAccounts;
import dev.carlosnobuyuki.bank.domain.Transfer;
import dev.carlosnobuyuki.bank.domain.TransferResult;
import dev.carlosnobuyuki.bank.domain.TransferStatus;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Round-trips the JPA adapters against PostgreSQL. Each test is rolled back afterwards. */
@IntegrationTest
@Transactional
class JpaRepositoriesTests {

  // PostgreSQL stores microseconds; truncating keeps round-trip comparisons exact.
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
  private static final String HASH = "b".repeat(64);
  private static final String LOCK_NOT_AVAILABLE = "55P03";

  @Autowired AccountRepository accounts;
  @Autowired TransferRepository transfers;
  @Autowired EntityManager entityManager;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;

  @Test
  void addsAndFindsAccount() {
    Account account = newCustomer();
    flushAndClear();

    Account found = accounts.findById(account.id()).orElseThrow();

    assertThat(found.type()).isEqualTo(AccountType.CUSTOMER);
    assertThat(found.isActive()).isTrue();
    assertThat(found.balance()).isEqualTo(Money.brl(0));
    assertThat(found.createdAt()).isEqualTo(NOW);
  }

  @Test
  void findByIdIsEmptyForUnknownAccount() {
    assertThat(accounts.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void lockForUpdateReturnsExistingAccountsInDatabaseIdOrder() {
    UUID a = newCustomer().id();
    UUID b = newCustomer().id();
    flushAndClear();
    // UUID.compareTo (signed longs) and PostgreSQL (unsigned bytes) can disagree; the lock order
    // that matters is the database's.
    List<UUID> expected =
        jdbc.queryForList(
            "SELECT id FROM accounts WHERE id IN (?, ?) ORDER BY id", UUID.class, a, b);

    assertThat(accounts.lockForUpdate(b, a))
        .extracting(Account::id)
        .containsExactlyElementsOf(expected);
    assertThat(accounts.lockForUpdate(a, UUID.randomUUID()))
        .extracting(Account::id)
        .containsExactly(a);
  }

  @Test
  void lockForUpdateHoldsRowLock() throws SQLException {
    UUID customer = newCustomer().id();
    entityManager.flush();

    // A second connection, outside the test's transaction. SYSTEM_FUNDING is committed by the
    // migration, so that connection can see the row and has to wait for any lock on it.
    try (Connection other = dataSource.getConnection()) {
      assertThat(lockIsBusy(other, SystemAccounts.FUNDING_ID)).isFalse();

      accounts.lockForUpdate(SystemAccounts.FUNDING_ID, customer);

      assertThat(lockIsBusy(other, SystemAccounts.FUNDING_ID)).isTrue();
    }
  }

  @Test
  void updateBalanceRefusesAccountThatIsNotLocked() {
    Account account = newCustomer();
    flushAndClear();
    Account unlocked = accounts.findById(account.id()).orElseThrow();

    // @Repository adapters translate exceptions; Spring reports IllegalStateException as misuse.
    assertThatThrownBy(() -> accounts.updateBalance(unlocked))
        .isInstanceOf(InvalidDataAccessApiUsageException.class)
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not locked");
  }

  @Test
  void addsCompletedTransferWithEntriesAndUpdatedBalances() {
    Account customer = newCustomer();
    flushAndClear();

    TransferResult result = fund(customer.id(), UUID.randomUUID(), 1_000);
    flushAndClear();

    Transfer stored = transfers.findById(result.transfer().id()).orElseThrow();
    assertThat(stored.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(stored.amount()).isEqualTo(Money.brl(1_000));
    assertThat(stored.requestHash()).isEqualTo(HASH);
    assertThat(stored.createdAt()).isEqualTo(NOW);
    assertThat(transfers.findByIdempotencyKey(stored.idempotencyKey()))
        .map(Transfer::id)
        .contains(stored.id());
    assertThat(accounts.findById(customer.id()).orElseThrow().balance())
        .isEqualTo(Money.brl(1_000));
    assertThat(
            jdbc.queryForList(
                "SELECT amount_minor FROM ledger_entries WHERE transfer_id = ? ORDER BY amount_minor",
                Long.class,
                stored.id()))
        .containsExactly(-1_000L, 1_000L);
  }

  @Test
  void addsRejectedTransferWithoutEntries() {
    UUID poor = newCustomer().id();
    UUID other = newCustomer().id();
    flushAndClear();
    List<Account> locked = accounts.lockForUpdate(poor, other);
    Account source = locked.get(0).id().equals(poor) ? locked.get(0) : locked.get(1);
    Account destination = locked.get(0).id().equals(poor) ? locked.get(1) : locked.get(0);

    TransferResult result =
        Transfer.execute(
            UUID.randomUUID(), UUID.randomUUID(), HASH, source, destination, Money.brl(1), NOW);
    transfers.add(result);
    flushAndClear();

    Transfer stored = transfers.findById(result.transfer().id()).orElseThrow();
    assertThat(stored.status()).isEqualTo(TransferStatus.REJECTED);
    assertThat(stored.rejectionReason()).contains(RejectionReason.INSUFFICIENT_FUNDS);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE transfer_id = ?",
                Long.class,
                stored.id()))
        .isZero();
  }

  @Test
  void reportsDuplicateIdempotencyKey() {
    UUID customer = newCustomer().id();
    flushAndClear();
    UUID key = UUID.randomUUID();
    fund(customer, key, 100);

    assertThatThrownBy(() -> fund(customer, key, 100))
        .isInstanceOf(DuplicateIdempotencyKeyException.class)
        .extracting(e -> ((DuplicateIdempotencyKeyException) e).idempotencyKey())
        .isEqualTo(key);
  }

  private Account newCustomer() {
    Account account = Account.open(UUID.randomUUID(), AccountType.CUSTOMER, Money.BRL, NOW);
    accounts.add(account);
    return account;
  }

  /** Moves money from SYSTEM_FUNDING the same way the use case will: lock, execute, store. */
  private TransferResult fund(UUID customerId, UUID idempotencyKey, long amountMinor) {
    List<Account> locked = accounts.lockForUpdate(SystemAccounts.FUNDING_ID, customerId);
    Account funding =
        locked.stream().filter(a -> a.type() == AccountType.SYSTEM).findFirst().orElseThrow();
    Account customer =
        locked.stream().filter(a -> a.id().equals(customerId)).findFirst().orElseThrow();
    TransferResult result =
        Transfer.execute(
            UUID.randomUUID(),
            idempotencyKey,
            HASH,
            funding,
            customer,
            Money.brl(amountMinor),
            NOW);
    transfers.add(result);
    accounts.updateBalance(funding);
    accounts.updateBalance(customer);
    return result;
  }

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }

  private static boolean lockIsBusy(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement("SELECT 1 FROM accounts WHERE id = ? FOR UPDATE NOWAIT")) {
      select.setObject(1, id);
      select.executeQuery().close();
      return false;
    } catch (SQLException e) {
      if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
        return true;
      }
      throw e;
    }
  }
}
