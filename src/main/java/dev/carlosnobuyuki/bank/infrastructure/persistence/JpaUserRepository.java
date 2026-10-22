package dev.carlosnobuyuki.bank.infrastructure.persistence;

import dev.carlosnobuyuki.bank.application.port.DuplicateUsernameException;
import dev.carlosnobuyuki.bank.application.port.UserRepository;
import dev.carlosnobuyuki.bank.domain.auth.User;
import dev.carlosnobuyuki.bank.domain.auth.Username;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

// Transactional here, not in the use case: the port promises each method is atomic on its own, so
// password hashing can happen between calls without holding a connection (ADR-0013).
@Repository
@Transactional
class JpaUserRepository implements UserRepository {

  // Must match the constraint name in V3__users.sql (ADR-0009).
  static final String USERNAME_CONSTRAINT = "users_username_unique";

  private final UserJpaRepository users;
  private final EntityManager entityManager;

  JpaUserRepository(UserJpaRepository users, EntityManager entityManager) {
    this.users = users;
    this.entityManager = entityManager;
  }

  @Override
  public void add(User user) {
    try {
      // Flush now, so a concurrent registration of the same name fails here as a typed exception.
      entityManager.persist(UserEntity.from(user));
      entityManager.flush();
    } catch (PersistenceException e) {
      if (ConstraintViolations.violates(e, USERNAME_CONSTRAINT)) {
        throw new DuplicateUsernameException(e);
      }
      throw e;
    }
  }

  @Override
  @Transactional(readOnly = true)
  public boolean existsByUsername(Username username) {
    return users.existsByUsername(username.value());
  }
}
