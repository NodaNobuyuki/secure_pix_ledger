package dev.carlosnobuyuki.bank.application.auth;

/**
 * Login failed. Deliberately says nothing about why: an unknown username and a wrong password must
 * look the same to the caller (ADR-0014).
 */
public class InvalidCredentialsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidCredentialsException() {
    super("invalid credentials");
  }
}
