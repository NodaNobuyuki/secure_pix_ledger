package dev.carlosnobuyuki.bank.application.auth;

import dev.carlosnobuyuki.bank.application.port.AccessToken;
import dev.carlosnobuyuki.bank.application.port.AccessTokenIssuer;
import dev.carlosnobuyuki.bank.application.port.PasswordHasher;
import dev.carlosnobuyuki.bank.application.port.UserRepository;
import dev.carlosnobuyuki.bank.domain.auth.InvalidPasswordException;
import dev.carlosnobuyuki.bank.domain.auth.InvalidUsernameException;
import dev.carlosnobuyuki.bank.domain.auth.Password;
import dev.carlosnobuyuki.bank.domain.auth.PasswordHash;
import dev.carlosnobuyuki.bank.domain.auth.User;
import dev.carlosnobuyuki.bank.domain.auth.Username;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/** Password login (ADR-0014). Every failure is the same {@link InvalidCredentialsException}. */
public class LoginService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserRepository users;
  private final PasswordHasher hasher;
  private final AccessTokenIssuer tokens;

  // Checked when the username is unknown, so that case costs one hash like a wrong password does
  // and the response time does not reveal which usernames exist. Made with the live hasher, so it
  // always has the same parameters as real hashes.
  private final PasswordHash dummyHash;

  public LoginService(UserRepository users, PasswordHasher hasher, AccessTokenIssuer tokens) {
    this.users = users;
    this.hasher = hasher;
    this.tokens = tokens;
    this.dummyHash = hasher.hash(randomPassword());
  }

  /**
   * Checks the password and issues an access token.
   *
   * <p>Not transactional, like registration: the lookup is atomic on its own, and hashing must not
   * hold a pooled connection (ADR-0013).
   *
   * @throws InvalidCredentialsException if the username is unknown, the password is wrong, or
   *     either could never have been registered
   */
  public AccessToken login(String username, String password) {
    Username name;
    Password secret;
    try {
      name = Username.parse(username);
      secret = new Password(password);
    } catch (InvalidUsernameException | InvalidPasswordException e) {
      // Format rules are public, so answering without hashing reveals nothing about which users
      // exist, and malformed requests cost no Argon2 work.
      throw new InvalidCredentialsException();
    }
    Optional<User> user = users.findByUsername(name);
    boolean matches = hasher.matches(secret, user.map(User::passwordHash).orElse(dummyHash));
    if (user.isEmpty() || !matches) {
      throw new InvalidCredentialsException();
    }
    return tokens.issue(user.get());
  }

  private static Password randomPassword() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return new Password(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
  }
}
