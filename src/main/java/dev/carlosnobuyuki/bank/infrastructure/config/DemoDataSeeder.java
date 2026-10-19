package dev.carlosnobuyuki.bank.infrastructure.config;

import dev.carlosnobuyuki.bank.application.DepositService;
import dev.carlosnobuyuki.bank.application.port.AccountRepository;
import dev.carlosnobuyuki.bank.domain.Account;
import dev.carlosnobuyuki.bank.domain.AccountType;
import dev.carlosnobuyuki.bank.domain.Money;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Funds two demo accounts with fixed ids so the README's curl examples work against a local stack.
 * Dev profile only: outside tests, this is the one caller of {@link DepositService} (ADR-0010).
 *
 * <p>Safe to run on every startup: accounts are created only if missing, and each deposit reuses a
 * fixed idempotency key, so later runs replay it instead of issuing the money again (ADR-0008).
 */
@Component
@Profile("dev")
class DemoDataSeeder implements ApplicationRunner {

  static final DemoAccount FUNDED =
      new DemoAccount(
          UUID.fromString("d0000000-0000-4000-8000-000000000001"),
          UUID.fromString("d0000000-0000-4000-8000-0000000000f1"),
          100_000);
  static final DemoAccount SMALL =
      new DemoAccount(
          UUID.fromString("d0000000-0000-4000-8000-000000000002"),
          UUID.fromString("d0000000-0000-4000-8000-0000000000f2"),
          5_000);
  static final List<DemoAccount> ACCOUNTS = List.of(FUNDED, SMALL);

  private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

  private final AccountRepository accounts;
  private final DepositService deposits;
  private final TransactionTemplate transaction;
  private final Clock clock;

  DemoDataSeeder(
      AccountRepository accounts,
      DepositService deposits,
      TransactionTemplate transaction,
      Clock clock) {
    this.accounts = accounts;
    this.deposits = deposits;
    this.transaction = transaction;
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) {
    for (DemoAccount demo : ACCOUNTS) {
      // Opening and funding are separate transactions: deposit() must not run inside an outer
      // one, or losing an idempotency race could not replay in a fresh transaction.
      transaction.executeWithoutResult(status -> openIfMissing(demo.id()));
      deposits.deposit(demo.depositKey(), demo.id(), Money.brl(demo.amountMinor()));
    }
    log.warn("dev profile: demo accounts {} and {} are funded", FUNDED.id(), SMALL.id());
  }

  private void openIfMissing(UUID id) {
    if (accounts.findById(id).isEmpty()) {
      accounts.add(
          Account.open(
              id, AccountType.CUSTOMER, Money.BRL, clock.instant().truncatedTo(ChronoUnit.MICROS)));
    }
  }

  record DemoAccount(UUID id, UUID depositKey, long amountMinor) {}
}
