package dev.carlosnobuyuki.bank.application.port;

import dev.carlosnobuyuki.bank.domain.auth.User;
import dev.carlosnobuyuki.bank.domain.auth.Username;

/**
 * Storage for users. Unlike the ledger repositories, each method is atomic on its own: use cases
 * call them around password hashing, which must not hold a database connection (ADR-0013).
 */
public interface UserRepository {

  /**
   * Stores a new user.
   *
   * @throws DuplicateUsernameException if the username is taken, including by a concurrent
   *     registration that passed the same {@link #existsByUsername} check
   */
  void add(User user);

  boolean existsByUsername(Username username);
}
