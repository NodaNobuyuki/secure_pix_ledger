package dev.carlosnobuyuki.bank.domain.auth;

import dev.carlosnobuyuki.bank.domain.DomainException;

/** The username breaks the format rule. The message is fixed and never repeats the input. */
public class InvalidUsernameException extends DomainException {

  private static final long serialVersionUID = 1L;

  public InvalidUsernameException() {
    super("must be 3 to 32 characters: letters, digits, '.', '_' or '-'");
  }
}
