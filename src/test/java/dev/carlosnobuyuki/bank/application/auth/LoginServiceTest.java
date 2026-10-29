package dev.carlosnobuyuki.bank.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.carlosnobuyuki.bank.application.port.AccessToken;
import dev.carlosnobuyuki.bank.application.port.AccessTokenIssuer;
import dev.carlosnobuyuki.bank.application.port.PasswordHasher;
import dev.carlosnobuyuki.bank.application.port.UserRepository;
import dev.carlosnobuyuki.bank.domain.auth.Password;
import dev.carlosnobuyuki.bank.domain.auth.PasswordHash;
import dev.carlosnobuyuki.bank.domain.auth.User;
import dev.carlosnobuyuki.bank.domain.auth.Username;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoginServiceTest {

  private static final String PASSWORD = "correct horse battery";

  private final FakeUsers users = new FakeUsers();
  private final FakeHasher hasher = new FakeHasher();
  private final List<User> issuedFor = new ArrayList<>();
  private final AccessTokenIssuer tokens =
      user -> {
        issuedFor.add(user);
        return new AccessToken("token-for-" + user.id(), Duration.ofMinutes(10));
      };

  private final LoginService login = new LoginService(users, hasher, tokens);

  private final User alice =
      User.register(
          UUID.randomUUID(),
          Username.parse("alice"),
          hasher.hash(new Password(PASSWORD)),
          Instant.parse("2026-10-29T12:00:00Z"));

  @Test
  void issuesATokenForTheRightPassword() {
    users.add(alice);

    AccessToken token = login.login("alice", PASSWORD);

    assertThat(token.value()).isEqualTo("token-for-" + alice.id());
    assertThat(issuedFor).containsExactly(alice);
  }

  @Test
  void usernameIsCaseInsensitive() {
    users.add(alice);

    login.login("ALICE", PASSWORD);

    assertThat(issuedFor).containsExactly(alice);
  }

  @Test
  void wrongPasswordIsRejected() {
    users.add(alice);

    assertThatThrownBy(() -> login.login("alice", "wrong horse battery"))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThat(issuedFor).isEmpty();
  }

  @Test
  void unknownUsernameStillPaysForOneHash() {
    // Same work as a wrong password, so timing does not reveal that "bob" does not exist.
    assertThatThrownBy(() -> login.login("bob", PASSWORD))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(hasher.checked).hasSize(1);
    assertThat(hasher.checked.getFirst()).isNotNull().isNotEqualTo(alice.passwordHash());
    assertThat(issuedFor).isEmpty();
  }

  @Test
  void theDummyHashComesFromTheLiveHasher() {
    // Its parameters then always match real hashes, which is what keeps the timing equal.
    FakeHasher fresh = new FakeHasher();

    new LoginService(users, fresh, tokens);

    assertThat(fresh.hashed).hasSize(1);
  }

  @Test
  void inputThatCouldNeverBeRegisteredIsRejectedWithoutTouchingStorage() {
    users.add(alice);

    for (String[] input :
        new String[][] {{"<script>", PASSWORD}, {"al", PASSWORD}, {"alice", "short"}}) {
      assertThatThrownBy(() -> login.login(input[0], input[1]))
          .isInstanceOf(InvalidCredentialsException.class);
    }
    assertThat(users.lookups).isZero();
    assertThat(hasher.checked).isEmpty();
  }

  @Test
  void theExceptionNeverSaysWhy() {
    users.add(alice);

    String unknown = message(() -> login.login("bob", PASSWORD));
    String wrong = message(() -> login.login("alice", "wrong horse battery"));
    String malformed = message(() -> login.login("<script>", PASSWORD));

    assertThat(unknown).isEqualTo(wrong).isEqualTo(malformed);
  }

  private static String message(Runnable call) {
    try {
      call.run();
    } catch (InvalidCredentialsException e) {
      return e.getMessage();
    }
    throw new AssertionError("expected InvalidCredentialsException");
  }

  private static final class FakeUsers implements UserRepository {

    private final Map<Username, User> byName = new HashMap<>();
    int lookups;

    @Override
    public void add(User user) {
      byName.put(user.username(), user);
    }

    @Override
    public boolean existsByUsername(Username username) {
      return byName.containsKey(username);
    }

    @Override
    public Optional<User> findByUsername(Username username) {
      lookups++;
      return Optional.ofNullable(byName.get(username));
    }
  }

  // Reversible on purpose: the tests care which hash was compared, not about real hashing.
  private static final class FakeHasher implements PasswordHasher {

    final List<Password> hashed = new ArrayList<>();
    final List<PasswordHash> checked = new ArrayList<>();

    @Override
    public PasswordHash hash(Password password) {
      hashed.add(password);
      return new PasswordHash("{fake}" + password.value());
    }

    @Override
    public boolean matches(Password password, PasswordHash hash) {
      checked.add(hash);
      return hash.encoded().equals("{fake}" + password.value());
    }
  }
}
