package dev.carlosnobuyuki.bank.infrastructure.persistence;

import dev.carlosnobuyuki.bank.application.port.DuplicateIdempotencyKeyException;
import dev.carlosnobuyuki.bank.application.port.TransferRepository;
import dev.carlosnobuyuki.bank.domain.Transfer;
import dev.carlosnobuyuki.bank.domain.TransferResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;

@Repository
class JpaTransferRepository implements TransferRepository {

  // Must match the constraint name in V1__initial_schema.sql (ADR-0009).
  static final String IDEMPOTENCY_KEY_CONSTRAINT = "transfers_idempotency_key_unique";

  private final TransferJpaRepository transfers;
  private final EntityManager entityManager;

  JpaTransferRepository(TransferJpaRepository transfers, EntityManager entityManager) {
    this.transfers = transfers;
    this.entityManager = entityManager;
  }

  @Override
  public void add(TransferResult result) {
    Transfer transfer = result.transfer();
    try {
      // Entry ids are IDENTITY, so persisting an entry already sends the pending INSERTs. The
      // flush covers transfers without entries (REJECTED). Either way a concurrent duplicate fails
      // inside this block, as a typed exception, not as a generic error at commit (ADR-0008).
      entityManager.persist(TransferEntity.from(transfer));
      result.entries().forEach(entry -> entityManager.persist(LedgerEntryEntity.from(entry)));
      entityManager.flush();
    } catch (PersistenceException e) {
      if (violates(e, IDEMPOTENCY_KEY_CONSTRAINT)) {
        throw new DuplicateIdempotencyKeyException(transfer.idempotencyKey(), e);
      }
      throw e;
    }
  }

  // Hibernate may throw its ConstraintViolationException directly or wrapped, so walk the causes.
  private static boolean violates(Throwable error, String constraintName) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof ConstraintViolationException violation) {
        return constraintName.equals(violation.getConstraintName());
      }
    }
    return false;
  }

  @Override
  public Optional<Transfer> findById(UUID id) {
    return transfers.findById(id).map(TransferEntity::toDomain);
  }

  @Override
  public Optional<Transfer> findByIdempotencyKey(UUID idempotencyKey) {
    return transfers.findByIdempotencyKey(idempotencyKey).map(TransferEntity::toDomain);
  }
}
