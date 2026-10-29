package dev.carlosnobuyuki.bank.infrastructure.config;

import dev.carlosnobuyuki.bank.application.AccountService;
import dev.carlosnobuyuki.bank.application.DepositService;
import dev.carlosnobuyuki.bank.application.TransferExecutor;
import dev.carlosnobuyuki.bank.application.TransferService;
import dev.carlosnobuyuki.bank.application.auth.LoginService;
import dev.carlosnobuyuki.bank.application.auth.RegistrationService;
import dev.carlosnobuyuki.bank.application.port.AccessTokenIssuer;
import dev.carlosnobuyuki.bank.application.port.AccountRepository;
import dev.carlosnobuyuki.bank.application.port.PasswordHasher;
import dev.carlosnobuyuki.bank.application.port.TransferRepository;
import dev.carlosnobuyuki.bank.application.port.UserRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the use cases. They carry no Spring stereotype annotations (ADR-0001), so they are
 * registered here; Spring still applies their {@code @Transactional} through a proxy.
 */
@Configuration(proxyBeanMethods = false)
class ApplicationConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  TransferExecutor transferExecutor(
      AccountRepository accounts, TransferRepository transfers, Clock clock) {
    return new TransferExecutor(accounts, transfers, clock);
  }

  @Bean
  TransferService transferService(
      TransferExecutor executor, TransferRepository transfers, AccountRepository accounts) {
    return new TransferService(executor, transfers, accounts);
  }

  @Bean
  DepositService depositService(TransferExecutor executor) {
    return new DepositService(executor);
  }

  @Bean
  AccountService accountService(AccountRepository accounts, Clock clock) {
    return new AccountService(accounts, clock);
  }

  @Bean
  RegistrationService registrationService(
      UserRepository users, PasswordHasher hasher, Clock clock) {
    return new RegistrationService(users, hasher, clock);
  }

  @Bean
  LoginService loginService(UserRepository users, PasswordHasher hasher, AccessTokenIssuer tokens) {
    return new LoginService(users, hasher, tokens);
  }
}
