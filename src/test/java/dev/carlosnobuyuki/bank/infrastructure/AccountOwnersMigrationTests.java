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
 * V4 must refuse a database that already holds customer accounts, which have no one to belong to,
 * instead of inventing an owner (ADR-0015). Local volumes created before V4 are in that state.
 *
 * <p>Runs on a scratch database in the shared container, so the application's database is
 * untouched.
 */
@IntegrationTest
class AccountOwnersMigrationTests {

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
