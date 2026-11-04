package dev.carlosnobuyuki.bank.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.carlosnobuyuki.bank.IntegrationTest;
import dev.carlosnobuyuki.bank.application.auth.LoginService;
import dev.carlosnobuyuki.bank.application.port.AccountRepository;
import dev.carlosnobuyuki.bank.application.port.UserRepository;
import dev.carlosnobuyuki.bank.domain.Account;
import dev.carlosnobuyuki.bank.domain.auth.Username;
import dev.carlosnobuyuki.bank.infrastructure.config.DemoDataSeeder.DemoAccount;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The seeder is the only non-test code that issues money, so both halves matter: it must be absent
 * by default, and with {@code dev} it must fund the accounts exactly once, each owned by its demo
 * user (ADR-0015).
 */
@IntegrationTest
class DemoDataSeederTests {

  private static final String DEMO_PASSWORD = "test-only-demo-password";

  // Fields live in each nested class: the enclosing instance is injected from the outer class's
  // context, which for WithDevProfile is the wrong one.

  @Nested
  class WithoutDevProfile {

    @Autowired ApplicationContext context;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;

    @Test
    void doesNotSeed() {
      assertThat(context.getBeanProvider(DemoDataSeeder.class).getIfAvailable()).isNull();
      assertThat(DemoDataSeeder.ACCOUNTS)
          .allSatisfy(
              demo -> {
                assertThat(accounts.findById(demo.id())).isEmpty();
                assertThat(users.existsByUsername(Username.parse(demo.username()))).isFalse();
              });
    }

    @Test
    void refusesToRunWithoutADemoPassword() {
      for (String missing : new String[] {"", "  "}) {
        assertThatThrownBy(
                () -> new DemoDataSeeder(null, null, null, null, null, Clock.systemUTC(), missing))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DEMO_USER_PASSWORD");
      }
    }
  }

  // A separate Spring context, and so a separate PostgreSQL container: the seeder runs at startup.
  @Nested
  @ActiveProfiles("dev")
  @TestPropertySource(properties = "DEMO_USER_PASSWORD=" + DEMO_PASSWORD)
  class WithDevProfile {

    @Autowired DemoDataSeeder seeder;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired LoginService login;
    @Autowired JdbcTemplate jdbc;

    @Test
    void fundsDemoAccountsOnceAcrossRestarts() {
      assertSeeded();

      // A second startup against the same database.
      seeder.run(new DefaultApplicationArguments());

      assertSeeded();
      assertThat(DemoDataSeeder.ACCOUNTS)
          .allSatisfy(demo -> assertThat(transfersWithKey(demo)).isEqualTo(1));
      assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class))
          .isEqualTo(DemoDataSeeder.ACCOUNTS.size());
    }

    @Test
    void demoUsersLogInWithTheConfiguredPassword() {
      assertThat(DemoDataSeeder.ACCOUNTS)
          .allSatisfy(
              demo -> assertThat(login.login(demo.username(), DEMO_PASSWORD).value()).isNotBlank());
    }

    private void assertSeeded() {
      assertThat(DemoDataSeeder.ACCOUNTS)
          .allSatisfy(
              demo -> {
                Account account = accounts.findById(demo.id()).orElseThrow();
                assertThat(account.isOwnedBy(userId(demo))).isTrue();
                assertThat(account.balance().amountMinor()).isEqualTo(demo.amountMinor());
              });
    }

    private UUID userId(DemoAccount demo) {
      return users.findByUsername(Username.parse(demo.username())).orElseThrow().id();
    }

    private long transfersWithKey(DemoAccount demo) {
      return jdbc.queryForObject(
          "SELECT count(*) FROM transfers WHERE idempotency_key = ?",
          Long.class,
          demo.depositKey());
    }
  }
}
