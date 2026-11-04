package dev.carlosnobuyuki.bank.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.carlosnobuyuki.bank.IntegrationTest;
import dev.carlosnobuyuki.bank.domain.SystemAccounts;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the database rejects invalid states on its own, bypassing the domain model entirely
 * (ADR-0006). Each test runs in a transaction that is rolled back afterwards.
 *
 * <p>Runs as the schema owner, not the application role: constraints and triggers must stop even
 * the role that owns the tables, while the application role cannot attempt most of these statements
 * at all (ADR-0012, proven by {@link LeastPrivilegeTests}).
 */
@IntegrationTest
class SchemaConstraintsTests {

  private static final String HASH = "a".repeat(64);
  private static final String ENCODED_HASH = "{argon2}$argon2id$v=19$m=19456,t=2,p=1$x$y";

  @Autowired PostgreSQLContainer postgres;

  private Connection owner;
  private JdbcTemplate jdbc;

  @BeforeEach
  void connectAsOwner() throws SQLException {
    owner =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    owner.setAutoCommit(false);
    jdbc = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
  }

  @AfterEach
  void rollBack() throws SQLException {
    try (Connection connection = owner) {
      connection.rollback();
    }
  }

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
    UUID owner = insertUser();

    assertThatThrownBy(() -> insertAccount("CUSTOMER", "FROZEN", owner, 0))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("accounts_status_valid");
  }

  @Test
  void rejectsCustomerAccountWithoutOwner() {
    assertThatThrownBy(() -> insertAccount("CUSTOMER", "ACTIVE", null, 0))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("accounts_owner_iff_customer");
  }

  @Test
  void rejectsSystemAccountWithOwner() {
    UUID owner = insertUser();

    assertThatThrownBy(() -> insertAccount("SYSTEM", "ACTIVE", owner, 0))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("accounts_owner_iff_customer");
  }

  @Test
  void rejectsOwnerThatIsNotAUser() {
    assertThatThrownBy(() -> insertAccount("CUSTOMER", "ACTIVE", UUID.randomUUID(), 0))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("accounts_owner_fk");
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
        .hasMessageContaining("transfers_initiator_idempotency_key_unique");
  }

  @Test
  void allowsSameKeyForDifferentInitiators() {
    UUID a = insertAccount("CUSTOMER", 0);
    UUID b = insertAccount("CUSTOMER", 0);
    UUID key = UUID.randomUUID();
    insertTransfer(key, a, b, 100, "COMPLETED", null);

    insertTransfer(key, b, a, 100, "COMPLETED", null);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM transfers WHERE idempotency_key = ?", Long.class, key))
        .isEqualTo(2L);
  }

  @Test
  void rejectsDuplicateKeyBetweenInternalTransfers() {
    // NULLS NOT DISTINCT: without it, two NULL initiators would never count as duplicates.
    UUID funding = insertAccount("SYSTEM", 0);
    UUID customer = insertAccount("CUSTOMER", 0);
    UUID key = UUID.randomUUID();
    insertTransfer(null, key, funding, customer, 100, "COMPLETED", null);

    assertThatThrownBy(() -> insertTransfer(null, key, funding, customer, 100, "COMPLETED", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("transfers_initiator_idempotency_key_unique");
  }

  @Test
  void rejectsInitiatorWhoDoesNotOwnTheSource() {
    UUID a = insertAccount("CUSTOMER", 0);
    UUID b = insertAccount("CUSTOMER", 0);
    UUID bsOwner = jdbc.queryForObject("SELECT owner_id FROM accounts WHERE id = ?", UUID.class, b);

    assertThatThrownBy(
            () ->
                insertTransfer(
                    bsOwner, UUID.randomUUID(), a, b, 100, "REJECTED", "INSUFFICIENT_FUNDS"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("transfers_initiator_owns_source");
  }

  @Test
  void rejectsCustomerTransferWithoutInitiator() {
    UUID a = insertAccount("CUSTOMER", 0);
    UUID b = insertAccount("CUSTOMER", 0);

    assertThatThrownBy(() -> insertTransfer(null, UUID.randomUUID(), a, b, 100, "COMPLETED", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("transfers_initiator_owns_source");
  }

  @Test
  void rejectsInitiatorOnInternalTransfer() {
    UUID funding = insertAccount("SYSTEM", 0);
    UUID customer = insertAccount("CUSTOMER", 0);
    UUID someone = insertUser();

    assertThatThrownBy(
            () ->
                insertTransfer(
                    someone, UUID.randomUUID(), funding, customer, 100, "COMPLETED", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("transfers_initiator_owns_source");
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

  @Test
  void rejectsUsernameThatIsNotNormalized() {
    assertThatThrownBy(() -> insertUser("Alice", "CUSTOMER", ENCODED_HASH))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("users_username_format");
  }

  @Test
  void rejectsDuplicateUsername() {
    insertUser("schema-dup", "CUSTOMER", ENCODED_HASH);

    assertThatThrownBy(() -> insertUser("schema-dup", "CUSTOMER", ENCODED_HASH))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("users_username_unique");
  }

  @Test
  void rejectsUnknownRole() {
    assertThatThrownBy(() -> insertUser("schema-root", "ROOT", ENCODED_HASH))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("users_role_valid");
  }

  @Test
  void rejectsPasswordHashWithoutAlgorithmPrefix() {
    assertThatThrownBy(() -> insertUser("schema-plain", "CUSTOMER", "correct horse battery"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("users_password_hash_encoded");
  }

  private UUID insertUser(String username, String role, String passwordHash) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, username, password_hash, role) VALUES (?, ?, ?, ?)",
        id,
        username,
        passwordHash,
        role);
    return id;
  }

  private UUID insertUser() {
    return insertUser(
        "schema-" + UUID.randomUUID().toString().substring(0, 8), "CUSTOMER", ENCODED_HASH);
  }

  private UUID insertAccount(String type, long balanceMinor) {
    return insertAccount(
        type, "ACTIVE", type.equals("CUSTOMER") ? insertUser() : null, balanceMinor);
  }

  private UUID insertAccount(String type, String status, UUID ownerId, long balanceMinor) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO accounts (id, type, status, currency, balance_minor, owner_id)"
            + " VALUES (?, ?, ?, 'BRL', ?, ?)",
        id,
        type,
        status,
        balanceMinor,
        ownerId);
    return id;
  }

  // Initiated by the source's owner, which is what the trigger requires (ADR-0015).
  private UUID insertTransfer(
      UUID idempotencyKey,
      UUID source,
      UUID destination,
      long amountMinor,
      String status,
      String rejectionReason) {
    UUID owner =
        jdbc.queryForObject("SELECT owner_id FROM accounts WHERE id = ?", UUID.class, source);
    return insertTransfer(
        owner, idempotencyKey, source, destination, amountMinor, status, rejectionReason);
  }

  private UUID insertTransfer(
      UUID initiatedBy,
      UUID idempotencyKey,
      UUID source,
      UUID destination,
      long amountMinor,
      String status,
      String rejectionReason) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO transfers (id, idempotency_key, request_hash, initiated_by,"
            + " source_account_id, destination_account_id, amount_minor, currency, status,"
            + " rejection_reason)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, 'BRL', ?, ?)",
        id,
        idempotencyKey,
        HASH,
        initiatedBy,
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
