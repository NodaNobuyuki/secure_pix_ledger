package dev.carlosnobuyuki.bank.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * An exact amount of money in minor units (centavos for BRL). See ADR-0006.
 *
 * <p>Any sign is allowed: ledger debits and the system funding balance are negative. Whether an
 * amount must be positive is a rule of the operation (e.g. a transfer), not of money itself.
 */
public record Money(long amountMinor, Currency currency) {

  public static final Currency BRL = Currency.getInstance("BRL");

  public Money {
    Objects.requireNonNull(currency, "currency");
  }

  public static Money brl(long amountMinor) {
    return new Money(amountMinor, BRL);
  }

  public static Money zero(Currency currency) {
    return new Money(0, currency);
  }

  // The *Exact variants throw on overflow; silently wrapping around would create money.
  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
  }

  public Money minus(Money other) {
    requireSameCurrency(other);
    return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
  }

  public Money negate() {
    return new Money(Math.negateExact(amountMinor), currency);
  }

  public boolean isPositive() {
    return amountMinor > 0;
  }

  public boolean isNegative() {
    return amountMinor < 0;
  }

  public boolean isZero() {
    return amountMinor == 0;
  }

  public boolean hasSameCurrencyAs(Money other) {
    return currency.equals(other.currency);
  }

  private void requireSameCurrency(Money other) {
    Objects.requireNonNull(other, "other");
    if (!hasSameCurrencyAs(other)) {
      throw new IllegalArgumentException(
          "currency mismatch: " + currency + " vs " + other.currency);
    }
  }
}
