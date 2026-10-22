package dev.carlosnobuyuki.bank.application.auth;

/** Registration chose a username that already exists (ADR-0013). */
public class UsernameTakenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UsernameTakenException() {
    super("username already taken");
  }

  public UsernameTakenException(Throwable cause) {
    super("username already taken", cause);
  }
}
