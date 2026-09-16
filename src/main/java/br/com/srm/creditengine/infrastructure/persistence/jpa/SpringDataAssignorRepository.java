package br.com.srm.creditengine.infrastructure.persistence.jpa;

import br.com.srm.creditengine.infrastructure.persistence.entity.AssignorJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataAssignorRepository extends JpaRepository<AssignorJpaEntity, UUID> {

    @Query("""
            SELECT a
              FROM AssignorJpaEntity a
             WHERE a.id = :id
            """)
    Optional<AssignorJpaEntity> findById(@Param("id") UUID id);

    @Query("""
            SELECT a
              FROM AssignorJpaEntity a
             WHERE a.document = :document
            """)
    Optional<AssignorJpaEntity> findByDocument(@Param("document") String document);
}
