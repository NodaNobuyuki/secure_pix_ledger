package dev.carlosnobuyuki.bank.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.carlosnobuyuki.bank.domain.InvalidTransferException;
import dev.carlosnobuyuki.bank.domain.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferCommandTest {

  private static final UUID KEY = UUID.randomUUID();
  private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Test
  void hashesCanonicalForm() {
    // Reference value from: printf 'v1|1111...|2222...|1050|BRL' | sha256sum
    assertThat(new TransferCommand(KEY, A, B, Money.brl(1_050)).requestHash())
        .isEqualTo("21e85cf558b8fa86da31fa5e967669fd1bd676acf32a5490e3d5144654eb0ff5");
  }

  @Test
  void hashIgnoresIdempotencyKey() {
    assertThat(new TransferCommand(UUID.randomUUID(), A, B, Money.brl(1)).requestHash())
        .isEqualTo(new TransferCommand(UUID.randomUUID(), A, B, Money.brl(1)).requestHash());
  }

  @Test
  void hashChangesWithEveryRequestField() {
    String base = new TransferCommand(KEY, A, B, Money.brl(100)).requestHash();

    assertThat(new TransferCommand(KEY, B, A, Money.brl(100)).requestHash()).isNotEqualTo(base);
    assertThat(new TransferCommand(KEY, A, B, Money.brl(101)).requestHash()).isNotEqualTo(base);
    assertThat(new TransferCommand(KEY, A, UUID.randomUUID(), Money.brl(100)).requestHash())
        .isNotEqualTo(base);
  }

  @Test
  void rejectsNonPositiveAmount() {
    assertThatThrownBy(() -> new TransferCommand(KEY, A, B, Money.brl(0)))
        .isInstanceOf(InvalidTransferException.class);
    assertThatThrownBy(() -> new TransferCommand(KEY, A, B, Money.brl(-1)))
        .isInstanceOf(InvalidTransferException.class);
  }

  @Test
  void rejectsSameSourceAndDestination() {
    assertThatThrownBy(() -> new TransferCommand(KEY, A, A, Money.brl(1)))
        .isInstanceOf(InvalidTransferException.class);
  }
}
