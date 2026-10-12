package dev.carlosnobuyuki.bank;

import org.springframework.boot.SpringApplication;

public class TestSecurePixLedgerApplication {

  public static void main(String[] args) {
    SpringApplication.from(SecurePixLedgerApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
