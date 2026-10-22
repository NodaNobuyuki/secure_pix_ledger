package dev.carlosnobuyuki.bank.domain.auth;

import dev.carlosnobuyuki.bank.domain.DomainException;

/** The password breaks the length rule. The message is fixed and never repeats the input. */
public class InvalidPasswordException extends DomainException {

  private static final long serialVersionUID = 1L;

  public InvalidPasswordException() {
    super("must be " + Password.MIN_LENGTH + " to " + Password.MAX_LENGTH + " characters long");
  }
}
