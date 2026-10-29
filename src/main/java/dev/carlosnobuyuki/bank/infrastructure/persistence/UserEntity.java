package dev.carlosnobuyuki.bank.infrastructure.persistence;

import dev.carlosnobuyuki.bank.domain.auth.PasswordHash;
import dev.carlosnobuyuki.bank.domain.auth.Role;
import dev.carlosnobuyuki.bank.domain.auth.User;
import dev.carlosnobuyuki.bank.domain.auth.Username;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// Every column is updatable = false: the application role has no UPDATE on users (ADR-0012), and
// nothing in the domain changes a user after registration yet.
@Entity
@Table(name = "users")
class UserEntity {

  @Id private UUID id;

  @Column(nullable = false, updatable = false, length = 32)
  private String username;

  @Column(name = "password_hash", nullable = false, updatable = false)
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private Role role;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected UserEntity() {
    // for JPA
  }

  static UserEntity from(User user) {
    UserEntity entity = new UserEntity();
    entity.id = user.id();
    entity.username = user.username().value();
    entity.passwordHash = user.passwordHash().encoded();
    entity.role = user.role();
    entity.createdAt = user.createdAt();
    return entity;
  }

  User toDomain() {
    return User.restore(
        id, new Username(username), new PasswordHash(passwordHash), role, createdAt);
  }
}
