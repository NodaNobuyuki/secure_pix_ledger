package dev.carlosnobuyuki.bank.infrastructure.config;

import dev.carlosnobuyuki.bank.application.DepositService;
import dev.carlosnobuyuki.bank.application.auth.RegistrationService;
import dev.carlosnobuyuki.bank.application.port.AccountRepository;
import dev.carlosnobuyuki.bank.application.port.UserRepository;
import dev.carlosnobuyuki.bank.domain.Account;
import dev.carlosnobuyuki.bank.domain.Money;
import dev.carlosnobuyuki.bank.domain.auth.User;
import dev.carlosnobuyuki.bank.domain.auth.Username;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates two demo users, each owning one funded account with a fixed id, so the README's curl
 * examples work against a local stack. Dev profile only: outside tests, this is the one caller of
 * {@link DepositService} (ADR-0010).
 *
 * <p>Safe to run on every startup: users and accounts are created only if missing, and each deposit
 * reuses a fixed idempotency key, so later runs replay it instead of issuing the money again
 * (ADR-0008).
 */
@Component
@Profile("dev")
// Final: the constructor throws, and a subclass could otherwise observe a half-built seeder.
final class DemoDataSeeder implements ApplicationRunner {

  static final DemoAccount ALICE =
      new DemoAccount(
          "alice",
          UUID.fromString("d0000000-0000-4000-8000-000000000001"),
          UUID.fromString("d0000000-0000-4000-8000-0000000000f1"),
          100_000);
  static final DemoAccount BOB =
      new DemoAccount(
          "bob",
          UUID.fromString("d0000000-0000-4000-8000-000000000002"),
          UUID.fromString("d0000000-0000-4000-8000-0000000000f2"),
          5_000);
  static final List<DemoAccount> ACCOUNTS = List.of(ALICE, BOB);

  private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

  private final UserRepository users;
  private final RegistrationService registration;
  private final AccountRepository accounts;
  private final DepositService deposits;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final String password;

  // No default password: a well-known one would be the first thing anyone tries against a dev
  // stack left reachable. Failing here stops startup before anything is created.
  DemoDataSeeder(
      UserRepository users,
      RegistrationService registration,
      AccountRepository accounts,
      DepositService deposits,
      TransactionTemplate transaction,
      Clock clock,
      @Value("${DEMO_USER_PASSWORD:}") String password) {
    if (password.isBlank()) {
      throw new IllegalStateException(
          "the dev profile needs DEMO_USER_PASSWORD for the demo users (see .env.example)");
    }
    this.users = users;
    this.registration = registration;
    this.accounts = accounts;
    this.deposits = deposits;
    this.transaction = transaction;
    this.clock = clock;
    this.password = password;
  }

  @Override
  public void run(ApplicationArguments args) {
    for (DemoAccount demo : ACCOUNTS) {
      UUID owner = registerIfMissing(demo.username());
      // Opening and funding are separate transactions: deposit() must not run inside an outer
      // one, or losing an idempotency race could not replay in a fresh transaction.
      transaction.executeWithoutResult(status -> openIfMissing(demo.id(), owner));
      deposits.deposit(demo.depositKey(), demo.id(), Money.brl(demo.amountMinor()));
    }
    // Never the password: logs outlive the stack.
    log.warn(
        "dev profile: demo users {} and {} own funded accounts {} and {}",
        ALICE.username(),
        BOB.username(),
        ALICE.id(),
        BOB.id());
  }

  // An existing user keeps the password it was created with, even if DEMO_USER_PASSWORD changed.
  private UUID registerIfMissing(String username) {
    return users
        .findByUsername(Username.parse(username))
        .map(User::id)
        .orElseGet(() -> registration.register(username, password).id());
  }

  private void openIfMissing(UUID id, UUID owner) {
    accounts
        .findById(id)
        .ifPresentOrElse(
            existing -> {
              // Someone else holding a demo id means the database is not what the README
              // describes; funding it would hand them the demo money.
              if (!existing.isOwnedBy(owner)) {
                throw new IllegalStateException("demo account " + id + " has another owner");
              }
            },
            () ->
                accounts.add(
                    Account.open(
                        id, owner, Money.BRL, clock.instant().truncatedTo(ChronoUnit.MICROS))));
  }

  record DemoAccount(String username, UUID id, UUID depositKey, long amountMinor) {}
}
