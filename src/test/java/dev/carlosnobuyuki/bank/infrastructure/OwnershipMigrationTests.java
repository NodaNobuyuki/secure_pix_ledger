package dev.carlosnobuyuki.bank.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.carlosnobuyuki.bank.IntegrationTest;
import dev.carlosnobuyuki.bank.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V4 and V5 must refuse databases holding customer accounts or transfers from before ownership
 * existed, instead of inventing an owner or an initiator (ADR-0015). Local volumes created before
 * those migrations are in that state.
 *
 * <p>Runs on a scratch database in the shared container, so the application's database is
 * untouched.
 */
@IntegrationTest
class OwnershipMigrationTests {

  @Autowired PostgreSQLContainer postgres;

  private final String database = "v4_guard_" + UUID.randomUUID().toString().replace("-", "");

  @BeforeEach
  void createDatabase() throws SQLException {
    executeAsOwner("CREATE DATABASE " + database);
  }

  @AfterEach
  void dropDatabase() throws SQLException {
    executeAsOwner("DROP DATABASE " + database);
  }

  @Test
  void refusesCustomerAccountsCreatedBeforeOwnership() {
    flyway("3").migrate();
    scratch()
        .update(
            "INSERT INTO accounts (id, type, status, currency, balance_minor)"
                + " VALUES (?, 'CUSTOMER', 'ACTIVE', 'BRL', 0)",
            UUID.randomUUID());

    assertThatThrownBy(() -> flyway("4").migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("reset the development database");
  }

  @Test
  void refusesCustomerTransfersCreatedBeforeInitiators() {
    flyway("4").migrate();
    JdbcTemplate jdbc = scratch();
    UUID owner = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, username, password_hash, role)"
            + " VALUES (?, 'migration-owner', '{argon2}x', 'CUSTOMER')",
        owner);
    UUID a = insertCustomerAccount(jdbc, owner);
    UUID b = insertCustomerAccount(jdbc, owner);
    jdbc.update(
        "INSERT INTO transfers (id, idempotency_key, request_hash, source_account_id,"
            + " destination_account_id, amount_minor, currency, status, rejection_reason)"
            + " VALUES (?, ?, ?, ?, ?, 1, 'BRL', 'REJECTED', 'INSUFFICIENT_FUNDS')",
        UUID.randomUUID(),
        UUID.randomUUID(),
        "c".repeat(64),
        a,
        b);

    assertThatThrownBy(() -> flyway("5").migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("reset the development database");
  }

  private static UUID insertCustomerAccount(JdbcTemplate jdbc, UUID owner) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO accounts (id, type, status, currency, balance_minor, owner_id)"
            + " VALUES (?, 'CUSTOMER', 'ACTIVE', 'BRL', 0, ?)",
        id,
        owner);
    return id;
  }

  private Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(scratchUrl(), postgres.getUsername(), postgres.getPassword())
        .placeholders(Map.of("app_role", TestcontainersConfiguration.APP_ROLE))
        .target(target)
        .load();
  }

  private JdbcTemplate scratch() {
    return new JdbcTemplate(
        new DriverManagerDataSource(scratchUrl(), postgres.getUsername(), postgres.getPassword()));
  }

  private String scratchUrl() {
    return "jdbc:postgresql://%s:%d/%s"
        .formatted(
            postgres.getHost(),
            postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
            database);
  }

  // CREATE and DROP DATABASE cannot run inside a transaction; a plain connection autocommits.
  private void executeAsOwner(String sql) throws SQLException {
    try (Connection owner =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Statement statement = owner.createStatement()) {
      statement.execute(sql);
    }
  }
}
