package dev.carlosnobuyuki.bank.infrastructure.persistence;

import org.hibernate.exception.ConstraintViolationException;

/** Recognizes a violated constraint by its explicit name (ADR-0009). */
final class ConstraintViolations {

  private ConstraintViolations() {}

  // Hibernate may throw its ConstraintViolationException directly or wrapped, so walk the causes.
  static boolean violates(Throwable error, String constraintName) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof ConstraintViolationException violation) {
        return constraintName.equals(violation.getConstraintName());
      }
    }
    return false;
  }
}
