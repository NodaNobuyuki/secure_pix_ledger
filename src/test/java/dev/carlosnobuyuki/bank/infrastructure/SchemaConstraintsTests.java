package dev.carlosnobuyuki.bank.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.carlosnobuyuki.bank.TestcontainersConfiguration;
import dev.carlosnobuyuki.bank.domain.SystemAccounts;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the database rejects invalid states on its own, bypassing the domain model entirely
 * (ADR-0006). Each test runs in a transaction that is rolled back afterwards.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class SchemaConstraintsTests {

  private static final String HASH = "a".repeat(64);

  @Autowired JdbcTemplate jdbc;

  @Test
  void seedsSystemFundingAccountAtZero() {
    Map<String, Object> row =
        jdbc.queryForMap("SELECT * FROM accounts WHERE id = ?", SystemAccounts.FUNDING_ID);
    // Other test classes share this database and may have committed deposits already, so "seeded
    // at zero" is checked as: the balance is exactly what its entries explain.
    Long entries =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries WHERE account_id = ?",
            Long.class,
            SystemAccounts.FUNDING_ID);

    assertThat(row)
        .containsEntry("type", "SYSTEM")
        .containsEntry("status", "ACTIVE")
        .containsEntry("currency", "BRL")
        .containsEntry("balance_minor", entries);
  }

  @Test
  void rejectsNegativeCustomerBalance() {
    assertThatThrownBy(() -> insertAccount("CUSTOMER", -1))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("accounts_customer_balance_non_negative");
  }

  @Test
  void allowsNegativeSystemBalance() {
    UUID id = insertAccount("SYSTEM", -500);

    assertThat(
            jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, id))
        .isEqualTo(-500L);
  }

  @Test
  void rejectsUnknownAccountStatus() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO accounts (id, type, status, currency, balance_minor)"
                        + " VALUES (?, 'CUSTOMER', 'FROZEN', 'BRL', 0)",
                    UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("accounts_status_valid");
  }

  @Test
  void rejectsNonPositiveTransferAmount() {
    UUID a = insertAccount("CUSTOMER", 0);
    UUID b = insertAccount("CUSTOMER", 0);

    assertThatThrownBy(() -> insertTransfer(UUID.randomUUID(), a, b, 0, "COMPLETED", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("transfers_amount_positive");
  }

  @Test
  void rejectsTransferToSameAccount() {
    UUID a = insertAccount("CUSTOMER", 0);

    assertThatThrownBy(() -> insertTransfer(UUID.randomUUID(), a, a, 100, "COMPLETED", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("transfers_distinct_accounts");
  }

  @Test
  void rejectsDuplicateIdempotencyKey() {
    UUID a = insertAccount("CUSTOMER", 0);
    UUID b = insertAccount("CUSTOMER", 0);
    UUID key = UUID.randomUUID();
    insertTransfer(key, a, b, 100, "COMPLETED", null);

    assertThatThrownBy(() -> insertTransfer(key, a, b, 100, "COMPLETED", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("transfers_idempotency_key_unique");
  }

  @Test
  void rejectsRejectedTransferWithoutReason() {
    UUID a = insertAccount("CUSTOMER", 0);
    UUID b = insertAccount("CUSTOMER", 0);

    assertThatThrownBy(() -> insertTransfer(UUID.randomUUID(), a, b, 100, "REJECTED", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("transfers_rejection_reason_iff_rejected");
  }

  @Test
  void rejectsCompletedTransferWithReason() {
    UUID a = insertAccount("CUSTOMER", 0);
    UUID b = insertAccount("CUSTOMER", 0);

    assertThatThrownBy(
            () -> insertTransfer(UUID.randomUUID(), a, b, 100, "COMPLETED", "INSUFFICIENT_FUNDS"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("transfers_rejection_reason_iff_rejected");
  }

  @Test
  void rejectsZeroLedgerEntry() {
    UUID a = insertAccount("CUSTOMER", 0);
    UUID b = insertAccount("CUSTOMER", 0);
    UUID transfer = insertTransfer(UUID.randomUUID(), a, b, 100, "COMPLETED", null);

    assertThatThrownBy(() -> insertEntry(transfer, a, 0))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ledger_entries_amount_non_zero");
  }

  @Test
  void rejectsLedgerEntryUpdate() {
    long entryId = insertCompletedTransferEntry();

    assertThatThrownBy(
            () -> jdbc.update("UPDATE ledger_entries SET amount_minor = 1 WHERE id = ?", entryId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void rejectsLedgerEntryDelete() {
    long entryId = insertCompletedTransferEntry();

    assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE id = ?", entryId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void rejectsLedgerTruncate() {
    insertCompletedTransferEntry();

    assertThatThrownBy(() -> jdbc.execute("TRUNCATE ledger_entries"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  private UUID insertAccount(String type, long balanceMinor) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO accounts (id, type, status, currency, balance_minor)"
            + " VALUES (?, ?, 'ACTIVE', 'BRL', ?)",
        id,
        type,
        balanceMinor);
    return id;
  }

  private UUID insertTransfer(
      UUID idempotencyKey,
      UUID source,
      UUID destination,
      long amountMinor,
      String status,
      String rejectionReason) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO transfers (id, idempotency_key, request_hash, source_account_id,"
            + " destination_account_id, amount_minor, currency, status, rejection_reason)"
            + " VALUES (?, ?, ?, ?, ?, ?, 'BRL', ?, ?)",
        id,
        idempotencyKey,
        HASH,
        source,
        destination,
        amountMinor,
        status,
        rejectionReason);
    return id;
  }

  private long insertEntry(UUID transferId, UUID accountId, long amountMinor) {
    return jdbc.queryForObject(
        "INSERT INTO ledger_entries (transfer_id, account_id, amount_minor)"
            + " VALUES (?, ?, ?) RETURNING id",
        Long.class,
        transferId,
        accountId,
        amountMinor);
  }

  private long insertCompletedTransferEntry() {
    UUID a = insertAccount("CUSTOMER", 0);
    UUID b = insertAccount("CUSTOMER", 0);
    UUID transfer = insertTransfer(UUID.randomUUID(), a, b, 100, "COMPLETED", null);
    insertEntry(transfer, a, -100);
    return insertEntry(transfer, b, 100);
  }
}
