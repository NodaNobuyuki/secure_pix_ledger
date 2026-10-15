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

  Optional<Transfer> findByIdempotencyKey(UUID idempotencyKey);
}
