package dev.carlosnobuyuki.bank.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {

  boolean existsByUsername(String username);

  Optional<UserEntity> findByUsername(String username);
}
