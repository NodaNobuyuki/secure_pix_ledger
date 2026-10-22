package dev.carlosnobuyuki.bank.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserTest {

  @Test
  void registrationAlwaysCreatesACustomer() {
    User user =
        User.register(
            UUID.randomUUID(),
            Username.parse("alice"),
            new PasswordHash("{argon2}$argon2id$x"),
            Instant.EPOCH);

    assertThat(user.role()).isEqualTo(Role.CUSTOMER);
  }
}
