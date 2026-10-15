package dev.carlosnobuyuki.bank.api;

import dev.carlosnobuyuki.bank.application.TransferCommand;
import dev.carlosnobuyuki.bank.domain.Money;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.Currency;
import java.util.UUID;

/**
 * Body of {@code POST /transfers}. Wrapper types so a missing field is reported as missing instead
 * of silently becoming 0. JSON coercion is strict (application.properties, ADR-0011): {@code 1.5}
 * or {@code "100"} for {@code amountMinor} are rejected, not truncated or parsed.
 */
record TransferRequest(
    @NotNull UUID sourceAccountId,
    @NotNull UUID destinationAccountId,
    @NotNull @Positive Long amountMinor,
    @NotNull @Pattern(regexp = "BRL", message = "only BRL is supported") String currency) {

  TransferCommand toCommand(UUID idempotencyKey) {
    return new TransferCommand(
        idempotencyKey,
        sourceAccountId,
        destinationAccountId,
        new Money(amountMinor, Currency.getInstance(currency)));
  }
}
