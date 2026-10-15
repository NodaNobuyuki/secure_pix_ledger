package dev.carlosnobuyuki.bank.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
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
}
