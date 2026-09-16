package br.com.srm.creditengine.infrastructure.persistence.entity;

import br.com.srm.creditengine.domain.assignor.Assignor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "assignors")
public class AssignorJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "document", nullable = false, length = 14, unique = true)
    private String document;

    @Column(name = "legal_name", nullable = false, length = 200)
    private String legalName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AssignorJpaEntity() {
    }

    public AssignorJpaEntity(UUID id, String document, String legalName, Instant createdAt) {
        this.id = id;
        this.document = document;
        this.legalName = legalName;
        this.createdAt = createdAt;
    }

    public static AssignorJpaEntity fromDomain(Assignor assignor) {
        return new AssignorJpaEntity(
                assignor.id(),
                assignor.document(),
                assignor.legalName(),
                assignor.createdAt()
        );
    }

    public Assignor toDomain() {
        return new Assignor(id, document, legalName, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getDocument() {
        return document;
    }

    public void setDocument(String document) {
        this.document = document;
    }

    public String getLegalName() {
        return legalName;
    }

    public void setLegalName(String legalName) {
        this.legalName = legalName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
