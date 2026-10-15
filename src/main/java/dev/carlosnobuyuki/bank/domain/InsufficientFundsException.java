package dev.carlosnobuyuki.bank.domain;

import java.util.UUID;

public class InsufficientFundsException extends DomainException {

  private static final long serialVersionUID = 1L;

  public InsufficientFundsException(UUID accountId) {
    super("insufficient funds in account " + accountId);
  }
}
