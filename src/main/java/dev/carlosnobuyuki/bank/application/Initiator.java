package dev.carlosnobuyuki.bank.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Who moves the money (ADR-0015). Sealed, so a {@code switch} over it must handle both cases.
 *
 * <p>Public use cases build a {@link Customer} from the caller's id; only internal use cases such
 * as {@link DepositService} build {@link Internal}. That is why it travels next to a {@link
 * TransferCommand} and not inside it: the API layer builds commands, and must have no way to claim
 * to be internal.
 */
public sealed interface Initiator {

  /** The user stored as {@code initiated_by}; empty for internal transfers. */
  Optional<UUID> userId();

  /** A customer: may only move money out of accounts they own, and only to customer accounts. */
  record Customer(UUID id) implements Initiator {

    public Customer {
      Objects.requireNonNull(id, "id");
    }

    @Override
    public Optional<UUID> userId() {
      return Optional.of(id);
    }
  }

  /** The system itself, e.g. a deposit from SYSTEM_FUNDING (ADR-0006, ADR-0010). */
  record Internal() implements Initiator {

    @Override
    public Optional<UUID> userId() {
      return Optional.empty();
    }
  }
}
