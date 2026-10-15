package dev.carlosnobuyuki.bank.application.port;

import dev.carlosnobuyuki.bank.domain.Account;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Storage for accounts. Every method must run inside the caller's transaction. */
public interface AccountRepository {

  void add(Account account);

  /** Plain read, no lock. Never use the result to change a balance. */
  Optional<Account> findById(UUID id);

  /**
   * Locks the given accounts for update in ascending id order (ADR-0007) and returns those that
   * exist, in that order. Missing ids are simply absent from the result.
   */
  List<Account> lockForUpdate(UUID first, UUID second);

  /**
   * Persists the account's new balance.
   *
   * <p>Fails if the account was not locked by {@link #lockForUpdate} in the current transaction.
   * That is a programming error, not a business outcome, so callers should not catch it.
   */
  void updateBalance(Account account);
}
