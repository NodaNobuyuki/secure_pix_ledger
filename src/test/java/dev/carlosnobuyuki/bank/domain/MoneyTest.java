package dev.carlosnobuyuki.bank.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {

  private static final Currency USD = Currency.getInstance("USD");

  @Test
  void addsAndSubtractsExactly() {
    assertThat(Money.brl(1050).plus(Money.brl(250))).isEqualTo(Money.brl(1300));
    assertThat(Money.brl(250).minus(Money.brl(1050))).isEqualTo(Money.brl(-800));
  }

  @Test
  void negates() {
    assertThat(Money.brl(500).negate()).isEqualTo(Money.brl(-500));
  }

  @Test
  void rejectsOverflowInsteadOfWrappingAround() {
    assertThatThrownBy(() -> Money.brl(Long.MAX_VALUE).plus(Money.brl(1)))
        .isInstanceOf(ArithmeticException.class);
    assertThatThrownBy(() -> Money.brl(Long.MIN_VALUE).minus(Money.brl(1)))
        .isInstanceOf(ArithmeticException.class);
    assertThatThrownBy(() -> Money.brl(Long.MIN_VALUE).negate())
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void rejectsMixingCurrencies() {
    assertThatThrownBy(() -> Money.brl(100).plus(new Money(100, USD)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("currency mismatch");
  }

  @Test
  void requiresCurrency() {
    assertThatThrownBy(() -> new Money(100, null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void reportsSign() {
    assertThat(Money.brl(1).isPositive()).isTrue();
    assertThat(Money.brl(-1).isNegative()).isTrue();
    assertThat(Money.zero(Money.BRL).isZero()).isTrue();
    assertThat(Money.zero(Money.BRL).isPositive()).isFalse();
  }
}
