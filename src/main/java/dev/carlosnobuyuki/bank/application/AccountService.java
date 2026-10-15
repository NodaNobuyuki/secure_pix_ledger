package dev.carlosnobuyuki.bank.application;

import dev.carlosnobuyuki.bank.application.port.AccountRepository;
import dev.carlosnobuyuki.bank.domain.Account;
import dev.carlosnobuyuki.bank.domain.AccountType;
import dev.carlosnobuyuki.bank.domain.Money;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Public account use cases. Only customer accounts are visible here (ADR-0010). */
public class AccountService {

  private final AccountRepository accounts;
  private final Clock clock;

  public AccountService(AccountRepository accounts, Clock clock) {
    this.accounts = accounts;
    this.clock = clock;
  }

  /** Opens a BRL customer account with a zero balance; money arrives only by transfer. */
  @Transactional
  public Account open() {
    Account account =
        Account.open(
            UUID.randomUUID(),
            AccountType.CUSTOMER,
            Money.BRL,
            clock.instant().truncatedTo(ChronoUnit.MICROS));
    accounts.add(account);
    return account;
  }

  @Transactional(readOnly = true)
  public Optional<Account> find(UUID id) {
    return accounts.findById(id).filter(account -> account.type() == AccountType.CUSTOMER);
  }
}
