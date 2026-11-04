package dev.carlosnobuyuki.bank.application;

import dev.carlosnobuyuki.bank.application.port.AccountRepository;
import dev.carlosnobuyuki.bank.application.port.TransferRepository;
import dev.carlosnobuyuki.bank.domain.Account;
import dev.carlosnobuyuki.bank.domain.AccountType;
import dev.carlosnobuyuki.bank.domain.Transfer;
import dev.carlosnobuyuki.bank.domain.TransferResult;
import dev.carlosnobuyuki.bank.domain.TransferStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs one transfer attempt in one transaction. Callers go through {@link TransferService} or
 * {@link DepositService}, which handle the idempotency race that a single transaction cannot.
 */
public class TransferExecutor {

  private final AccountRepository accounts;
  private final TransferRepository transfers;
  private final Clock clock;

  public TransferExecutor(AccountRepository accounts, TransferRepository transfers, Clock clock) {
    this.accounts = accounts;
    this.transfers = transfers;
    this.clock = clock;
  }

  /**
   * Executes the command for the initiator, or replays the transfer the initiator already stored
   * under its key.
   *
   * @throws AccountNotFoundException if an account does not exist or the initiator may not use it
   *     (ADR-0010, ADR-0015); nothing is stored and the key stays free
   * @throws dev.carlosnobuyuki.bank.application.port.DuplicateIdempotencyKeyException if a
   *     concurrent request stored the key first; this transaction is rolled back, and the caller
   *     should {@link #replay} in a new one
   */
  @Transactional
  public TransferOutcome execute(TransferCommand command, Initiator initiator) {
    // Fast path for sequential retries: no locks needed.
    var existing = stored(command, initiator);
    if (existing.isPresent()) {
      return replayOf(existing.get(), command);
    }

    // Before locking: otherwise anyone who knows an account id could hold its lock with transfers
    // bound to fail, delaying the owner's real ones (ADR-0015). Safe without a lock because an
    // account's owner never changes.
    requireMayDebit(command.sourceAccountId(), initiator);

    List<Account> locked =
        accounts.lockForUpdate(command.sourceAccountId(), command.destinationAccountId());
    Account source = visible(locked, command.sourceAccountId(), initiator);
    Account destination = visible(locked, command.destinationAccountId(), initiator);

    TransferResult result =
        Transfer.execute(
            UUID.randomUUID(),
            command.idempotencyKey(),
            command.requestHash(),
            initiator.userId().orElse(null),
            source,
            destination,
            command.amount(),
            now());
    transfers.add(result);
    if (result.transfer().status() == TransferStatus.COMPLETED) {
      accounts.updateBalance(source);
      accounts.updateBalance(destination);
    }
    return new TransferOutcome(result.transfer(), false);
  }

  /** Replays the transfer stored under the command's key, after losing the race to store it. */
  @Transactional(readOnly = true)
  public TransferOutcome replay(TransferCommand command, Initiator initiator) {
    Transfer stored =
        stored(command, initiator)
            // The unique violation is only raised once the winner has committed.
            .orElseThrow(
                () -> new IllegalStateException("no transfer for " + command.idempotencyKey()));
    return replayOf(stored, command);
  }

  private Optional<Transfer> stored(TransferCommand command, Initiator initiator) {
    return transfers.findByIdempotencyKey(
        initiator.userId().orElse(null), command.idempotencyKey());
  }

  private static TransferOutcome replayOf(Transfer stored, TransferCommand command) {
    if (!stored.requestHash().equals(command.requestHash())) {
      throw new IdempotencyKeyReusedException(command.idempotencyKey());
    }
    return new TransferOutcome(stored, true);
  }

  // Someone else's account, an internal one and a missing one all get the same answer.
  private void requireMayDebit(UUID sourceId, Initiator initiator) {
    switch (initiator) {
      case Initiator.Customer(UUID userId) -> {
        // The owner only, never the account: loading it here would make the locked read below
        // return this unlocked, possibly stale balance (see AccountRepository#findById).
        if (!accounts.findOwnerId(sourceId).filter(userId::equals).isPresent()) {
          throw new AccountNotFoundException(sourceId);
        }
      }
      case Initiator.Internal() -> {
        // Internal use cases may move money out of any account.
      }
    }
  }

  // Customers only ever see customer accounts, on either side (ADR-0010).
  private static Account visible(List<Account> locked, UUID id, Initiator initiator) {
    return locked.stream()
        .filter(account -> account.id().equals(id))
        .filter(
            account ->
                switch (initiator) {
                  case Initiator.Customer c -> account.type() == AccountType.CUSTOMER;
                  case Initiator.Internal() -> true;
                })
        .findFirst()
        .orElseThrow(() -> new AccountNotFoundException(id));
  }

  // PostgreSQL keeps microseconds; truncating here makes a replay return the same instant.
  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
