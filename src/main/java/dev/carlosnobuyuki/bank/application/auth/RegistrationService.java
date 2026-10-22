package dev.carlosnobuyuki.bank.application.auth;

import dev.carlosnobuyuki.bank.application.port.DuplicateUsernameException;
import dev.carlosnobuyuki.bank.application.port.PasswordHasher;
import dev.carlosnobuyuki.bank.application.port.UserRepository;
import dev.carlosnobuyuki.bank.domain.auth.Password;
import dev.carlosnobuyuki.bank.domain.auth.User;
import dev.carlosnobuyuki.bank.domain.auth.Username;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Self-service sign-up. Always creates a customer; admins are never self-registered (ADR-0013). */
public class RegistrationService {

  private final UserRepository users;
  private final PasswordHasher hasher;
  private final Clock clock;

  public RegistrationService(UserRepository users, PasswordHasher hasher, Clock clock) {
    this.users = users;
    this.hasher = hasher;
    this.clock = clock;
  }

  /**
   * Registers a customer.
   *
   * <p>Deliberately not transactional: hashing takes tens of milliseconds and must not hold a
   * pooled connection meanwhile. The unique constraint, not the lookup, is what guarantees one user
   * per name.
   *
   * @throws dev.carlosnobuyuki.bank.domain.auth.InvalidUsernameException if the name is malformed
   * @throws dev.carlosnobuyuki.bank.domain.auth.InvalidPasswordException if the password is too
   *     short or too long
   * @throws UsernameTakenException if the name is already registered
   */
  public User register(String username, String password) {
    Username name = Username.parse(username);
    Password secret = new Password(password);
    // Cheap early answer, and no hashing work for names that are obviously taken.
    if (users.existsByUsername(name)) {
      throw new UsernameTakenException();
    }
    User user =
        User.register(
            UUID.randomUUID(),
            name,
            hasher.hash(secret),
            clock.instant().truncatedTo(ChronoUnit.MICROS));
    try {
      users.add(user);
    } catch (DuplicateUsernameException e) {
      throw new UsernameTakenException(e);
    }
    return user;
  }
}
