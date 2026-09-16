package br.com.srm.creditengine.infrastructure.persistence.jpa;

import br.com.srm.creditengine.domain.receivable.ReceivableStatus;
import br.com.srm.creditengine.infrastructure.persistence.entity.ReceivableJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataReceivableRepository extends JpaRepository<ReceivableJpaEntity, UUID> {

    @Query("""
            SELECT r
              FROM ReceivableJpaEntity r
             WHERE r.id = :id
            """)
    Optional<ReceivableJpaEntity> findById(@Param("id") UUID id);

    @Modifying
    @Query("""
            UPDATE ReceivableJpaEntity r
               SET r.status = :targetStatus,
                   r.version = r.version + 1
             WHERE r.id = :id
               AND r.version = :version
               AND r.status = :expectedStatus
            """)
    int updateStatusWithOptimisticLock(@Param("id") UUID id,
                                       @Param("version") long version,
                                       @Param("expectedStatus") ReceivableStatus expectedStatus,
                                       @Param("targetStatus") ReceivableStatus targetStatus);
}
