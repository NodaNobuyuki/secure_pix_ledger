package dev.carlosnobuyuki.bank.domain;

import java.util.UUID;

public class InactiveAccountException extends DomainException {

  private static final long serialVersionUID = 1L;

  public InactiveAccountException(UUID accountId) {
    super("account " + accountId + " is not active");
  }
}
