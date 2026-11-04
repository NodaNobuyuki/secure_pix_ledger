package dev.carlosnobuyuki.bank.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface TransferJpaRepository extends JpaRepository<TransferEntity, UUID> {

  // Two queries rather than one with a nullable parameter: "initiated_by = NULL" never matches in
  // SQL, so the internal case needs IS NULL, and derived queries make that explicit.
  Optional<TransferEntity> findByInitiatedByAndIdempotencyKey(UUID initiatedBy, UUID key);

  Optional<TransferEntity> findByInitiatedByIsNullAndIdempotencyKey(UUID key);
}
