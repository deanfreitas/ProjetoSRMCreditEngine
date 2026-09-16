package br.com.srm.creditengine.infrastructure.persistence.jpa;

import br.com.srm.creditengine.domain.money.Currency;
import br.com.srm.creditengine.infrastructure.persistence.entity.FxRateJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataFxRateRepository extends JpaRepository<FxRateJpaEntity, UUID> {

    @Query("""
            SELECT f
              FROM FxRateJpaEntity f
             WHERE f.id = :id
            """)
    Optional<FxRateJpaEntity> findById(@Param("id") UUID id);

    @Query("""
            SELECT f
              FROM FxRateJpaEntity f
             WHERE f.baseCurrency = :baseCurrency
               AND f.quoteCurrency = :quoteCurrency
               AND f.effectiveAt <= :effectiveAt
             ORDER BY f.effectiveAt DESC
             LIMIT 1
            """)
    Optional<FxRateJpaEntity> findFirstByBaseCurrencyAndQuoteCurrencyAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(
            @Param("baseCurrency") Currency baseCurrency,
            @Param("quoteCurrency") Currency quoteCurrency,
            @Param("effectiveAt") Instant effectiveAt);

    @Query("""
            SELECT f
              FROM FxRateJpaEntity f
             WHERE f.baseCurrency = :baseCurrency
               AND f.quoteCurrency = :quoteCurrency
             ORDER BY f.effectiveAt DESC
            """)
    List<FxRateJpaEntity> findByBaseCurrencyAndQuoteCurrencyOrderByEffectiveAtDesc(
            @Param("baseCurrency") Currency baseCurrency,
            @Param("quoteCurrency") Currency quoteCurrency,
            Pageable pageable);
}
