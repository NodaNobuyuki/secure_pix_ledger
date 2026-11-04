package dev.carlosnobuyuki.bank.application.port;

import dev.carlosnobuyuki.bank.domain.Transfer;
import dev.carlosnobuyuki.bank.domain.TransferResult;
import java.util.Optional;
import java.util.UUID;

/** Storage for transfers and their ledger entries. Both are append-only. */
public interface TransferRepository {

  /**
   * Inserts the transfer and its entries immediately (not at commit), so a key collision surfaces
   * here.
   *
   * @throws DuplicateIdempotencyKeyException if another transfer already uses the key; the current
   *     transaction can then only be rolled back
   */
  void add(TransferResult result);

  Optional<Transfer> findById(UUID id);

  /**
   * The transfer the initiator stored under this key. Keys are scoped per initiator (ADR-0008), so
   * another user's transfer with the same key is never returned.
   *
   * @param initiatedBy the initiating user, or {@code null} for internal transfers
   */
  Optional<Transfer> findByIdempotencyKey(UUID initiatedBy, UUID idempotencyKey);
}
