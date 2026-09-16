package br.com.srm.creditengine.infrastructure.persistence.jpa;

import br.com.srm.creditengine.infrastructure.persistence.entity.SettlementJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataSettlementRepository extends JpaRepository<SettlementJpaEntity, UUID> {

    @Query("""
            SELECT s
              FROM SettlementJpaEntity s
             WHERE s.id = :id
            """)
    Optional<SettlementJpaEntity> findById(@Param("id") UUID id);

    @Query("""
            SELECT s
              FROM SettlementJpaEntity s
             WHERE s.idempotencyKey = :idempotencyKey
            """)
    Optional<SettlementJpaEntity> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    @Query("""
            SELECT s
              FROM SettlementJpaEntity s
             WHERE s.receivableId = :receivableId
            """)
    Optional<SettlementJpaEntity> findByReceivableId(@Param("receivableId") UUID receivableId);
}
