package dev.carlosnobuyuki.bank.domain;

/** Base type for violations of business rules, so adapters can map them without catching all. */
public abstract class DomainException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  protected DomainException(String message) {
    super(message);
  }
}
