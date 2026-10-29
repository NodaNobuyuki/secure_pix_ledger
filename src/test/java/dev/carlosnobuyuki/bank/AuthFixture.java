package dev.carlosnobuyuki.bank;

import dev.carlosnobuyuki.bank.application.auth.LoginService;
import dev.carlosnobuyuki.bank.application.auth.RegistrationService;
import java.util.UUID;

/**
 * Registers and logs in users through the real services, so API tests call the endpoints the way a
 * client does: with a token the application itself issued (ADR-0014).
 */
public final class AuthFixture {

  public static final String PASSWORD = "correct horse battery";

  private final RegistrationService registration;
  private final LoginService login;

  AuthFixture(RegistrationService registration, LoginService login) {
    this.registration = registration;
    this.login = login;
  }

  /** A new customer with a unique name; tests share one database. */
  public LoggedIn newCustomer() {
    String username = "user-" + UUID.randomUUID().toString().substring(0, 13);
    UUID id = registration.register(username, PASSWORD).id();
    return new LoggedIn(id, username, "Bearer " + login.login(username, PASSWORD).value());
  }

  /**
   * A logged-in user.
   *
   * @param authorization the value for the {@code Authorization} header
   */
  public record LoggedIn(UUID id, String username, String authorization) {}
}
