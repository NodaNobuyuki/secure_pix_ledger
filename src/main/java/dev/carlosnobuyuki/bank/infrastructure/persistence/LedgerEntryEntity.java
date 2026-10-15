package dev.carlosnobuyuki.bank.infrastructure.persistence;

import dev.carlosnobuyuki.bank.domain.LedgerEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

// Write-only for now: entries are inserted with their transfer and read back only by SQL checks.
// The database trigger is the real append-only guard; @Immutable just keeps Hibernate from trying.
@Entity
@Immutable
@Table(name = "ledger_entries")
class LedgerEntryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "transfer_id", nullable = false)
  private UUID transferId;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  protected LedgerEntryEntity() {
    // for JPA
  }

  static LedgerEntryEntity from(LedgerEntry entry) {
    LedgerEntryEntity entity = new LedgerEntryEntity();
    entity.transferId = entry.transferId();
    entity.accountId = entry.accountId();
    entity.amountMinor = entry.amount().amountMinor();
    return entity;
  }
}
