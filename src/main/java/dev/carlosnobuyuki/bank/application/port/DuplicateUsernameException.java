package dev.carlosnobuyuki.bank.application.port;

/** Another user already has this username; the database's unique constraint caught it. */
public class DuplicateUsernameException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public DuplicateUsernameException(Throwable cause) {
    super("username already taken", cause);
  }
}
