package dev.carlosnobuyuki.bank.application;

import dev.carlosnobuyuki.bank.application.TransferExecutor.AccountScope;
import dev.carlosnobuyuki.bank.application.port.AccountRepository;
import dev.carlosnobuyuki.bank.application.port.DuplicateIdempotencyKeyException;
import dev.carlosnobuyuki.bank.application.port.TransferRepository;
import dev.carlosnobuyuki.bank.domain.AccountType;
import dev.carlosnobuyuki.bank.domain.Transfer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Public transfer use cases. Internal accounts are invisible here (ADR-0010). */
public class TransferService {

  private final TransferExecutor executor;
  private final TransferRepository transfers;
  private final AccountRepository accounts;

  public TransferService(
      TransferExecutor executor, TransferRepository transfers, AccountRepository accounts) {
    this.executor = executor;
    this.transfers = transfers;
    this.accounts = accounts;
  }

  /**
   * Moves money between two customer accounts, at most once per idempotency key (ADR-0008).
   *
   * <p>Deliberately not transactional: losing the key race rolls back the attempt's transaction,
   * and the replay needs a new one. Calling this inside an outer transaction breaks that.
   */
  public TransferOutcome transfer(TransferCommand command) {
    try {
      return executor.execute(command, AccountScope.CUSTOMER_ONLY);
    } catch (DuplicateIdempotencyKeyException e) {
      return executor.replay(command);
    }
  }

  /** A transfer between customer accounts; transfers touching internal accounts are hidden. */
  @Transactional(readOnly = true)
  public Optional<Transfer> find(UUID id) {
    return transfers
        .findById(id)
        .filter(t -> isCustomer(t.sourceAccountId()) && isCustomer(t.destinationAccountId()));
  }

  private boolean isCustomer(UUID accountId) {
    return accounts
        .findById(accountId)
        .map(account -> account.type() == AccountType.CUSTOMER)
        .orElse(false);
  }
}
