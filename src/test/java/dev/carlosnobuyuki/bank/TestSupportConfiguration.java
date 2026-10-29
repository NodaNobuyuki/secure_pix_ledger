package dev.carlosnobuyuki.bank;

import dev.carlosnobuyuki.bank.application.auth.LoginService;
import dev.carlosnobuyuki.bank.application.auth.RegistrationService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test-only beans shared by every integration test. */
@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfiguration {

  // Primary, so it replaces the system clock everywhere, including token issuing and checking.
  @Bean
  @Primary
  MutableClock mutableClock() {
    return new MutableClock();
  }

  @Bean
  AuthFixture authFixture(RegistrationService registration, LoginService login) {
    return new AuthFixture(registration, login);
  }
}
