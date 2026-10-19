package dev.carlosnobuyuki.bank.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.carlosnobuyuki.bank.IntegrationTest;
import dev.carlosnobuyuki.bank.application.AccountService;
import dev.carlosnobuyuki.bank.infrastructure.config.DemoDataSeeder.DemoAccount;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The seeder is the only non-test code that issues money, so both halves matter: it must be absent
 * by default, and with {@code dev} it must fund the accounts exactly once.
 */
@IntegrationTest
class DemoDataSeederTests {

  // Fields live in each nested class: the enclosing instance is injected from the outer class's
  // context, which for WithDevProfile is the wrong one.

  @Nested
  class WithoutDevProfile {

    @Autowired ApplicationContext context;
    @Autowired AccountService accounts;

    @Test
    void doesNotSeed() {
      assertThat(context.getBeanProvider(DemoDataSeeder.class).getIfAvailable()).isNull();
      assertThat(DemoDataSeeder.ACCOUNTS)
          .allSatisfy(demo -> assertThat(accounts.find(demo.id())).isEmpty());
    }
  }

  // A separate Spring context, and so a separate PostgreSQL container: the seeder runs at startup.
  @Nested
  @ActiveProfiles("dev")
  class WithDevProfile {

    @Autowired DemoDataSeeder seeder;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;

    @Test
    void fundsDemoAccountsOnceAcrossRestarts() {
      assertFunded();

      // A second startup against the same database.
      seeder.run(new DefaultApplicationArguments());

      assertFunded();
      assertThat(DemoDataSeeder.ACCOUNTS)
          .allSatisfy(demo -> assertThat(transfersWithKey(demo)).isEqualTo(1));
    }

    private void assertFunded() {
      assertThat(DemoDataSeeder.ACCOUNTS)
          .allSatisfy(
              demo ->
                  assertThat(accounts.find(demo.id()).orElseThrow().balance().amountMinor())
                      .isEqualTo(demo.amountMinor()));
    }

    private long transfersWithKey(DemoAccount demo) {
      return jdbc.queryForObject(
          "SELECT count(*) FROM transfers WHERE idempotency_key = ?",
          Long.class,
          demo.depositKey());
    }
  }
}
