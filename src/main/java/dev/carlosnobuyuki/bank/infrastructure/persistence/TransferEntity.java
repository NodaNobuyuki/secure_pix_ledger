package dev.carlosnobuyuki.bank.infrastructure.persistence;

import dev.carlosnobuyuki.bank.domain.Money;
import dev.carlosnobuyuki.bank.domain.RejectionReason;
import dev.carlosnobuyuki.bank.domain.Transfer;
import dev.carlosnobuyuki.bank.domain.TransferStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// A transfer's outcome is final when it is stored; @Immutable makes Hibernate skip dirty checks
// and never issue an UPDATE for it.
@Entity
@Immutable
@Table(name = "transfers")
class TransferEntity {

  @Id private UUID id;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  // Plain ids rather than @ManyToOne: the domain refers to accounts by id, and this avoids
  // lazy-loading accounts by accident (and without a lock).
  @Column(name = "source_account_id", nullable = false)
  private UUID sourceAccountId;

  @Column(name = "destination_account_id", nullable = false)
  private UUID destinationAccountId;

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TransferStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "rejection_reason")
  private RejectionReason rejectionReason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected TransferEntity() {
    // for JPA
  }

  static TransferEntity from(Transfer transfer) {
    TransferEntity entity = new TransferEntity();
    entity.id = transfer.id();
    entity.idempotencyKey = transfer.idempotencyKey();
    entity.requestHash = transfer.requestHash();
    entity.sourceAccountId = transfer.sourceAccountId();
    entity.destinationAccountId = transfer.destinationAccountId();
    entity.amountMinor = transfer.amount().amountMinor();
    entity.currency = transfer.amount().currency().getCurrencyCode();
    entity.status = transfer.status();
    entity.rejectionReason = transfer.rejectionReason().orElse(null);
    entity.createdAt = transfer.createdAt();
    return entity;
  }

  Transfer toDomain() {
    return Transfer.restore(
        id,
        idempotencyKey,
        requestHash,
        sourceAccountId,
        destinationAccountId,
        new Money(amountMinor, Currency.getInstance(currency)),
        status,
        rejectionReason,
        createdAt);
  }
}
