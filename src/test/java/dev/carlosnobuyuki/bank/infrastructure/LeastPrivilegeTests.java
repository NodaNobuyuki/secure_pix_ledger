package dev.carlosnobuyuki.bank.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import dev.carlosnobuyuki.bank.IntegrationTest;
import dev.carlosnobuyuki.bank.TestcontainersConfiguration;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The application's own connection cannot rewrite history, remove the ledger's guards or change an
 * account's type, even if the application is compromised (ADR-0012).
 */
@IntegrationTest
class LeastPrivilegeTests {

  // SQLSTATE insufficient_privilege: both "permission denied" and "must be owner".
  private static final String INSUFFICIENT_PRIVILEGE = "42501";

  @Autowired JdbcTemplate jdbc;

  @Test
  void connectsAsTheRestrictedRole() {
    // Without this, the refusals below could pass for other reasons, or a superuser connection
    // would make most of them succeed.
    Map<String, Object> role =
        jdbc.queryForMap(
            "SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls"
                + " FROM pg_roles WHERE rolname = current_user");

    assertThat(role)
        .containsEntry("rolname", TestcontainersConfiguration.APP_ROLE)
        .containsEntry("rolsuper", false)
        .containsEntry("rolcreatedb", false)
        .containsEntry("rolcreaterole", false)
        .containsEntry("rolreplication", false)
        .containsEntry("rolbypassrls", false);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "UPDATE ledger_entries SET amount_minor = 1",
        "DELETE FROM ledger_entries",
        "TRUNCATE ledger_entries",
        "ALTER TABLE ledger_entries DISABLE TRIGGER ALL",
        "DROP TRIGGER ledger_entries_no_update_or_delete ON ledger_entries",
        "UPDATE transfers SET status = 'COMPLETED'",
        "DELETE FROM transfers",
        "UPDATE accounts SET type = 'SYSTEM'",
        "DELETE FROM accounts",
        "ALTER TABLE accounts DROP CONSTRAINT accounts_customer_balance_non_negative",
        "DROP TABLE accounts",
        "CREATE TABLE shadow_ledger (id INT)",
        "DELETE FROM flyway_schema_history"
      })
  void refuses(String sql) {
    assertThatThrownBy(() -> jdbc.execute(sql))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .asInstanceOf(type(SQLException.class))
        .extracting(SQLException::getSQLState)
        .isEqualTo(INSUFFICIENT_PRIVILEGE);
  }
}
