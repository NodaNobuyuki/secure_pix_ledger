package dev.carlosnobuyuki.bank.application;

import dev.carlosnobuyuki.bank.application.port.AccountRepository;
import dev.carlosnobuyuki.bank.domain.Account;
import dev.carlosnobuyuki.bank.domain.Money;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public account use cases. A caller only ever sees accounts they own: anyone else's, and internal
 * ones, look like accounts that do not exist (ADR-0010, ADR-0015).
 */
public class AccountService {

  private final AccountRepository accounts;
  private final Clock clock;

  public AccountService(AccountRepository accounts, Clock clock) {
    this.accounts = accounts;
    this.clock = clock;
  }

  /** Opens a BRL account for the owner with a zero balance; money arrives only by transfer. */
  @Transactional
  public Account open(UUID ownerId) {
    Account account =
        Account.open(
            UUID.randomUUID(), ownerId, Money.BRL, clock.instant().truncatedTo(ChronoUnit.MICROS));
    accounts.add(account);
    return account;
  }

  /** The account, if the requester owns it. Empty otherwise, whether or not it exists. */
  @Transactional(readOnly = true)
  public Optional<Account> find(UUID id, UUID requesterId) {
    return accounts.findById(id).filter(account -> account.isOwnedBy(requesterId));
  }

  /** The owner's accounts, oldest first. */
  @Transactional(readOnly = true)
  public List<Account> listOwnedBy(UUID ownerId) {
    return accounts.findByOwner(ownerId);
  }
}
