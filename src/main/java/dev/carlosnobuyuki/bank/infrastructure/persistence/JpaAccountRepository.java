package dev.carlosnobuyuki.bank.infrastructure.persistence;

import dev.carlosnobuyuki.bank.application.port.AccountRepository;
import dev.carlosnobuyuki.bank.domain.Account;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaAccountRepository implements AccountRepository {

  private final AccountJpaRepository accounts;
  private final EntityManager entityManager;

  JpaAccountRepository(AccountJpaRepository accounts, EntityManager entityManager) {
    this.accounts = accounts;
    this.entityManager = entityManager;
  }

  @Override
  public void add(Account account) {
    // persist, not accounts.save(): our ids are assigned up front, so save() would treat the
    // entity as existing and merge it, which costs a SELECT before every INSERT.
    entityManager.persist(AccountEntity.from(account));
  }

  @Override
  public Optional<Account> findById(UUID id) {
    return accounts.findById(id).map(AccountEntity::toDomain);
  }

  @Override
  public List<Account> lockForUpdate(UUID first, UUID second) {
    return accounts.lockAllOrderedById(List.of(first, second)).stream()
        .map(AccountEntity::toDomain)
        .toList();
  }

  @Override
  public void updateBalance(Account account) {
    AccountEntity entity = entityManager.find(AccountEntity.class, account.id());
    // Writing a balance read without the row lock is exactly the lost update ADR-0007 prevents,
    // so refuse it instead of trusting every caller.
    if (entity == null || entityManager.getLockMode(entity) != LockModeType.PESSIMISTIC_WRITE) {
      throw new IllegalStateException("account " + account.id() + " is not locked for update");
    }
    // A managed entity: Hibernate's dirty checking issues the UPDATE at flush or commit.
    entity.setBalance(account.balance());
  }
}
