package dev.carlosnobuyuki.bank;

import dev.carlosnobuyuki.bank.application.auth.LoginService;
import dev.carlosnobuyuki.bank.application.auth.RegistrationService;
import dev.carlosnobuyuki.bank.application.port.PasswordHasher;
import dev.carlosnobuyuki.bank.application.port.UserRepository;
import dev.carlosnobuyuki.bank.domain.auth.Password;
import dev.carlosnobuyuki.bank.domain.auth.PasswordHash;
import dev.carlosnobuyuki.bank.domain.auth.Role;
import dev.carlosnobuyuki.bank.domain.auth.User;
import dev.carlosnobuyuki.bank.domain.auth.Username;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Registers and logs in users through the real services, so API tests call the endpoints the way a
 * client does: with a token the application itself issued (ADR-0014).
 */
public final class AuthFixture {

  public static final String PASSWORD = "correct horse battery";

  private final RegistrationService registration;
  private final LoginService login;
  private final UserRepository users;
  private final Clock clock;
  // Hashed once per context: Argon2 costs 19 MiB and tens of milliseconds a call (ADR-0013), and
  // most tests only need a user to own an account.
  private final PasswordHash passwordHash;

  AuthFixture(
      RegistrationService registration,
      LoginService login,
      UserRepository users,
      PasswordHasher hasher,
      Clock clock) {
    this.registration = registration;
    this.login = login;
    this.users = users;
    this.clock = clock;
    this.passwordHash = hasher.hash(new Password(PASSWORD));
  }

  /** A new customer with a unique name; tests share one database. */
  public LoggedIn newCustomer() {
    String username = uniqueName();
    UUID id = registration.register(username, PASSWORD).id();
    return logIn(id, username);
  }

  /** A new customer stored directly, with no hashing and no token: an owner for test accounts. */
  public UUID newAccountOwner() {
    return insert(Role.CUSTOMER).id();
  }

  /** Admins are never self-registered (ADR-0013), so the fixture stores one directly. */
  public LoggedIn newAdmin() {
    User admin = insert(Role.ADMIN);
    return logIn(admin.id(), admin.username().value());
  }

  private User insert(Role role) {
    User user =
        User.restore(
            UUID.randomUUID(),
            Username.parse(uniqueName()),
            passwordHash,
            role,
            clock.instant().truncatedTo(ChronoUnit.MICROS));
    users.add(user);
    return user;
  }

  private LoggedIn logIn(UUID id, String username) {
    return new LoggedIn(id, username, "Bearer " + login.login(username, PASSWORD).value());
  }

  private static String uniqueName() {
    return "user-" + UUID.randomUUID().toString().substring(0, 13);
  }

  /**
   * A logged-in user.
   *
   * @param authorization the value for the {@code Authorization} header
   */
  public record LoggedIn(UUID id, String username, String authorization) {}
}
