package dev.carlosnobuyuki.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Asserts the double-entry invariants (ADR-0006) after each test: all entries sum to zero and every
 * materialized balance equals the sum of its account's entries.
 */
final class LedgerInvariantExtension implements AfterEachCallback {

  private static final String GLOBAL_SUM =
      "SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries";

  private static final String DRIFTED_ACCOUNTS =
      """
      SELECT a.id FROM accounts a
      LEFT JOIN (SELECT account_id, SUM(amount_minor) AS total
                 FROM ledger_entries GROUP BY account_id) e ON e.account_id = a.id
      WHERE a.balance_minor <> COALESCE(e.total, 0)
      """;

  @Override
  public void afterEach(ExtensionContext context) throws SQLException {
    DataSource dataSource =
        SpringExtension.getApplicationContext(context).getBean(DataSource.class);
    // A raw pool connection, not JdbcTemplate: in @Transactional tests the rollback happens after
    // this callback, and JdbcTemplate would join that transaction and see its uncommitted rows.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      try (ResultSet sum = statement.executeQuery(GLOBAL_SUM)) {
        sum.next();
        assertThat(sum.getLong(1)).as("all entries sum to zero").isZero();
      }
      List<String> drifted = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery(DRIFTED_ACCOUNTS)) {
        while (rows.next()) {
          drifted.add(rows.getString(1));
        }
      }
      assertThat(drifted)
          .as("accounts whose balance differs from the sum of their entries")
          .isEmpty();
    }
  }
}
