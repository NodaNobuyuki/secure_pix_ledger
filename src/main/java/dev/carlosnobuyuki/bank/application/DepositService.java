package dev.carlosnobuyuki.bank.application;

import dev.carlosnobuyuki.bank.application.TransferExecutor.AccountScope;
import dev.carlosnobuyuki.bank.application.port.DuplicateIdempotencyKeyException;
import dev.carlosnobuyuki.bank.domain.Money;
import dev.carlosnobuyuki.bank.domain.SystemAccounts;
import java.util.UUID;

/**
 * Internal use case that issues money: a transfer from SYSTEM_FUNDING (ADR-0006). Only seeds, tests
 * and the dev profile use it; it is a separate class so that no public endpoint ever gets it
 * injected by accident (ADR-0010).
 */
public class DepositService {

  private final TransferExecutor executor;

  public DepositService(TransferExecutor executor) {
    this.executor = executor;
  }

  /** Same idempotency and transaction rules as {@link TransferService#transfer}. */
  public TransferOutcome deposit(UUID idempotencyKey, UUID accountId, Money amount) {
    TransferCommand command =
        new TransferCommand(idempotencyKey, SystemAccounts.FUNDING_ID, accountId, amount);
    try {
      return executor.execute(command, AccountScope.ANY);
    } catch (DuplicateIdempotencyKeyException e) {
      return executor.replay(command);
    }
  }
}
