package br.com.srm.creditengine.infrastructure.persistence.entity;

import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.domain.money.Money;
import br.com.srm.creditengine.domain.pricing.ReceivableType;
import br.com.srm.creditengine.domain.receivable.Receivable;
import br.com.srm.creditengine.domain.receivable.ReceivableStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "receivables")
public class ReceivableJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "assignor_id", nullable = false)
    private UUID assignorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "receivable_type", nullable = false, length = 30)
    private ReceivableType receivableType;

    @Column(name = "face_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal faceValue;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "face_currency", nullable = false, length = 3)
    private Currency faceCurrency;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReceivableStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public ReceivableJpaEntity() {
    }

    public ReceivableJpaEntity(UUID id, UUID assignorId, ReceivableType receivableType, BigDecimal faceValue,
                               Currency faceCurrency, LocalDate dueDate, ReceivableStatus status, long version) {
        this.id = id;
        this.assignorId = assignorId;
        this.receivableType = receivableType;
        this.faceValue = faceValue;
        this.faceCurrency = faceCurrency;
        this.dueDate = dueDate;
        this.status = status;
        this.version = version;
    }

    public static ReceivableJpaEntity fromDomain(Receivable receivable) {
        return new ReceivableJpaEntity(
                receivable.id(),
                receivable.assignorId(),
                receivable.type(),
                receivable.faceValue().rounded().amount(),
                receivable.faceValue().currency(),
                receivable.dueDate(),
                receivable.status(),
                receivable.version()
        );
    }

    public Receivable toDomain() {
        return new Receivable(
                id,
                assignorId,
                receivableType,
                Money.of(faceValue, faceCurrency),
                dueDate,
                status,
                version
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAssignorId() {
        return assignorId;
    }

    public void setAssignorId(UUID assignorId) {
        this.assignorId = assignorId;
    }

    public ReceivableType getReceivableType() {
        return receivableType;
    }

    public void setReceivableType(ReceivableType receivableType) {
        this.receivableType = receivableType;
    }

    public BigDecimal getFaceValue() {
        return faceValue;
    }

    public void setFaceValue(BigDecimal faceValue) {
        this.faceValue = faceValue;
    }

    public Currency getFaceCurrency() {
        return faceCurrency;
    }

    public void setFaceCurrency(Currency faceCurrency) {
        this.faceCurrency = faceCurrency;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public ReceivableStatus getStatus() {
        return status;
    }

    public void setStatus(ReceivableStatus status) {
        this.status = status;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
