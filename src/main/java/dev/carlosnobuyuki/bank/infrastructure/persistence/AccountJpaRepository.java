package dev.carlosnobuyuki.bank.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface AccountJpaRepository extends JpaRepository<AccountEntity, UUID> {

  // One query, sorted by id: PostgreSQL locks rows as it returns them, so every transaction takes
  // the locks in the same order and two crossed transfers cannot deadlock (ADR-0007).
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from AccountEntity a where a.id in :ids order by a.id")
  List<AccountEntity> lockAllOrderedById(Collection<UUID> ids);

  // A scalar, not an entity: nothing enters the persistence context, so a later locked read of the
  // same row still returns its current balance.
  @Query("select a.ownerId from AccountEntity a where a.id = :id")
  Optional<UUID> findOwnerIdById(UUID id);

  // The id breaks ties between accounts opened in the same microsecond, so the order is stable.
  List<AccountEntity> findByOwnerIdOrderByCreatedAtAscIdAsc(UUID ownerId);
}
