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
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs one transfer attempt in one transaction. Callers go through {@link TransferService} or
 * {@link DepositService}, which handle the idempotency race that a single transaction cannot.
 */
public class TransferExecutor {

  /** Which accounts a caller may move money between (ADR-0010). */
  public enum AccountScope {
    /** Public use cases: internal accounts behave as if they did not exist. */
    CUSTOMER_ONLY,
    /** Internal use cases such as deposits from SYSTEM_FUNDING. */
    ANY
  }

  private final AccountRepository accounts;
  private final TransferRepository transfers;
  private final Clock clock;

  public TransferExecutor(AccountRepository accounts, TransferRepository transfers, Clock clock) {
    this.accounts = accounts;
    this.transfers = transfers;
    this.clock = clock;
  }

  /**
   * Executes the command, or replays the transfer already stored under its key.
   *
   * @throws dev.carlosnobuyuki.bank.application.port.DuplicateIdempotencyKeyException if a
   *     concurrent request stored the key first; this transaction is rolled back, and the caller
   *     should {@link #replay} in a new one
   */
  @Transactional
  public TransferOutcome execute(TransferCommand command, AccountScope scope) {
    // Fast path for sequential retries: no locks needed.
    var existing = transfers.findByIdempotencyKey(command.idempotencyKey());
    if (existing.isPresent()) {
      return replayOf(existing.get(), command);
    }

    List<Account> locked =
        accounts.lockForUpdate(command.sourceAccountId(), command.destinationAccountId());
    Account source = visible(locked, command.sourceAccountId(), scope);
    Account destination = visible(locked, command.destinationAccountId(), scope);

    TransferResult result =
        Transfer.execute(
            UUID.randomUUID(),
            command.idempotencyKey(),
            command.requestHash(),
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
  public TransferOutcome replay(TransferCommand command) {
    Transfer stored =
        transfers
            .findByIdempotencyKey(command.idempotencyKey())
            // The unique violation is only raised once the winner has committed.
            .orElseThrow(
                () -> new IllegalStateException("no transfer for " + command.idempotencyKey()));
    return replayOf(stored, command);
  }

  private static TransferOutcome replayOf(Transfer stored, TransferCommand command) {
    if (!stored.requestHash().equals(command.requestHash())) {
      throw new IdempotencyKeyReusedException(command.idempotencyKey());
    }
    return new TransferOutcome(stored, true);
  }

  private static Account visible(List<Account> locked, UUID id, AccountScope scope) {
    return locked.stream()
        .filter(account -> account.id().equals(id))
        .filter(account -> scope == AccountScope.ANY || account.type() == AccountType.CUSTOMER)
        .findFirst()
        .orElseThrow(() -> new AccountNotFoundException(id));
  }

  // PostgreSQL keeps microseconds; truncating here makes a replay return the same instant.
  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
