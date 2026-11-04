package dev.carlosnobuyuki.bank.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A request to move money between two accounts, and its outcome. Immutable. */
public final class Transfer {

  private final UUID id;
  private final UUID idempotencyKey;
  private final String requestHash;
  private final UUID initiatedBy;
  private final UUID sourceAccountId;
  private final UUID destinationAccountId;
  private final Money amount;
  private final TransferStatus status;
  private final RejectionReason rejectionReason;
  private final Instant createdAt;

  private Transfer(
      UUID id,
      UUID idempotencyKey,
      String requestHash,
      UUID initiatedBy,
      UUID sourceAccountId,
      UUID destinationAccountId,
      Money amount,
      TransferStatus status,
      RejectionReason rejectionReason,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    this.requestHash = Objects.requireNonNull(requestHash, "requestHash");
    this.initiatedBy = initiatedBy;
    this.sourceAccountId = Objects.requireNonNull(sourceAccountId, "sourceAccountId");
    this.destinationAccountId =
        Objects.requireNonNull(destinationAccountId, "destinationAccountId");
    this.amount = Objects.requireNonNull(amount, "amount");
    this.status = Objects.requireNonNull(status, "status");
    this.rejectionReason = rejectionReason;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if ((status == TransferStatus.REJECTED) != (rejectionReason != null)) {
      throw new IllegalArgumentException("a rejection reason is required exactly when REJECTED");
    }
  }

  /**
   * Applies the transfer to the two (already locked) accounts.
   *
   * <p>Invalid requests throw {@link InvalidTransferException}. Business-rule failures return a
   * REJECTED transfer and leave both balances untouched. Success debits, credits and returns the
   * two entries, which always sum to zero.
   *
   * @param initiatedBy the user moving the money, who must own the source; {@code null} for
   *     internal transfers, whose source belongs to no one (ADR-0015)
   */
  public static TransferResult execute(
      UUID id,
      UUID idempotencyKey,
      String requestHash,
      UUID initiatedBy,
      Account source,
      Account destination,
      Money amount,
      Instant now) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(amount, "amount");
    // Callers check ownership first and answer "not found" (ADR-0015); reaching this is a bug, so
    // it is not a business outcome. Same rule as the transfers_initiator_owns_source trigger.
    if (!Objects.equals(initiatedBy, source.ownerId().orElse(null))) {
      throw new IllegalArgumentException(
          "the initiator does not own source account " + source.id());
    }
    if (!amount.isPositive()) {
      throw new InvalidTransferException("amount must be positive");
    }
    if (source.id().equals(destination.id())) {
      throw new InvalidTransferException("source and destination must differ");
    }
    if (!amount.hasSameCurrencyAs(source.balance())
        || !amount.hasSameCurrencyAs(destination.balance())) {
      throw new InvalidTransferException("amount currency does not match the accounts");
    }

    RejectionReason reason = rejectionReasonFor(source, destination, amount);
    if (reason != null) {
      Transfer rejected =
          new Transfer(
              id,
              idempotencyKey,
              requestHash,
              initiatedBy,
              source.id(),
              destination.id(),
              amount,
              TransferStatus.REJECTED,
              reason,
              now);
      return new TransferResult(rejected, List.of());
    }

    source.debit(amount);
    destination.credit(amount);
    Transfer completed =
        new Transfer(
            id,
            idempotencyKey,
            requestHash,
            initiatedBy,
            source.id(),
            destination.id(),
            amount,
            TransferStatus.COMPLETED,
            null,
            now);
    return new TransferResult(
        completed,
        List.of(
            new LedgerEntry(id, source.id(), amount.negate()),
            new LedgerEntry(id, destination.id(), amount)));
  }

  /**
   * Rebuilds a transfer from storage, e.g. to replay it for an idempotent retry.
   *
   * @param initiatedBy {@code null} for internal transfers
   */
  public static Transfer restore(
      UUID id,
      UUID idempotencyKey,
      String requestHash,
      UUID initiatedBy,
      UUID sourceAccountId,
      UUID destinationAccountId,
      Money amount,
      TransferStatus status,
      RejectionReason rejectionReason,
      Instant createdAt) {
    return new Transfer(
        id,
        idempotencyKey,
        requestHash,
        initiatedBy,
        sourceAccountId,
        destinationAccountId,
        amount,
        status,
        rejectionReason,
        createdAt);
  }

  private static RejectionReason rejectionReasonFor(
      Account source, Account destination, Money amount) {
    if (!source.isActive()) {
      return RejectionReason.SOURCE_INACTIVE;
    }
    if (!destination.isActive()) {
      return RejectionReason.DESTINATION_INACTIVE;
    }
    if (!source.canDebit(amount)) {
      return RejectionReason.INSUFFICIENT_FUNDS;
    }
    return null;
  }

  public UUID id() {
    return id;
  }

  public UUID idempotencyKey() {
    return idempotencyKey;
  }

  public String requestHash() {
    return requestHash;
  }

  /** The user who moved the money; empty for internal transfers such as deposits. */
  public Optional<UUID> initiatedBy() {
    return Optional.ofNullable(initiatedBy);
  }

  public UUID sourceAccountId() {
    return sourceAccountId;
  }

  public UUID destinationAccountId() {
    return destinationAccountId;
  }

  public Money amount() {
    return amount;
  }

  public TransferStatus status() {
    return status;
  }

  public Optional<RejectionReason> rejectionReason() {
    return Optional.ofNullable(rejectionReason);
  }

  public Instant createdAt() {
    return createdAt;
  }
}
