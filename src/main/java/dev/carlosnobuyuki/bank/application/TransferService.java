package dev.carlosnobuyuki.bank.application;

import dev.carlosnobuyuki.bank.application.port.AccountRepository;
import dev.carlosnobuyuki.bank.application.port.DuplicateIdempotencyKeyException;
import dev.carlosnobuyuki.bank.application.port.TransferRepository;
import dev.carlosnobuyuki.bank.domain.Account;
import dev.carlosnobuyuki.bank.domain.AccountType;
import dev.carlosnobuyuki.bank.domain.Transfer;
import dev.carlosnobuyuki.bank.domain.TransferStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public transfer use cases. Customers move money out of their own accounts only, and see only
 * transfers they took part in; internal accounts are invisible here (ADR-0010, ADR-0015).
 */
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
   * Moves money from one of the caller's accounts to any customer account, at most once per caller
   * and idempotency key (ADR-0008).
   *
   * <p>Deliberately not transactional: losing the key race rolls back the attempt's transaction,
   * and the replay needs a new one. Calling this inside an outer transaction breaks that.
   *
   * @throws AccountNotFoundException if the caller does not own the source, or the destination is
   *     not a customer account; the key is not consumed
   */
  public TransferOutcome transfer(UUID callerId, TransferCommand command) {
    Initiator initiator = new Initiator.Customer(callerId);
    try {
      return executor.execute(command, initiator);
    } catch (DuplicateIdempotencyKeyException e) {
      return executor.replay(command, initiator);
    }
  }

  /**
   * The transfer, if the requester may see it: the payer always, the payee once it completed. A
   * rejection never touched the payee and would reveal the payer's state, so it stays private.
   * Transfers touching internal accounts are hidden from everyone here (ADR-0010).
   */
  @Transactional(readOnly = true)
  public Optional<Transfer> find(UUID id, UUID requesterId) {
    return transfers.findById(id).filter(transfer -> isVisibleTo(transfer, requesterId));
  }

  private boolean isVisibleTo(Transfer transfer, UUID requesterId) {
    Optional<Account> source = customerAccount(transfer.sourceAccountId());
    Optional<Account> destination = customerAccount(transfer.destinationAccountId());
    if (source.isEmpty() || destination.isEmpty()) {
      return false;
    }
    return source.get().isOwnedBy(requesterId)
        || (transfer.status() == TransferStatus.COMPLETED
            && destination.get().isOwnedBy(requesterId));
  }

  private Optional<Account> customerAccount(UUID accountId) {
    return accounts.findById(accountId).filter(account -> account.type() == AccountType.CUSTOMER);
  }
}
