package br.com.srm.creditengine.infrastructure.persistence;

import br.com.srm.creditengine.domain.settlement.Settlement;
import br.com.srm.creditengine.domain.settlement.SettlementRepository;
import br.com.srm.creditengine.infrastructure.persistence.entity.SettlementJpaEntity;
import br.com.srm.creditengine.infrastructure.persistence.jpa.SpringDataSettlementRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaSettlementRepository implements SettlementRepository {

    private final SpringDataSettlementRepository springDataRepository;

    public JpaSettlementRepository(SpringDataSettlementRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public Settlement save(Settlement settlement) {
        SettlementJpaEntity entity = SettlementJpaEntity.fromDomain(settlement);
        SettlementJpaEntity saved = springDataRepository.saveAndFlush(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<Settlement> findByIdempotencyKey(String idempotencyKey) {
        return springDataRepository.findByIdempotencyKey(idempotencyKey).map(SettlementJpaEntity::toDomain);
    }

    @Override
    public Optional<Settlement> findByReceivableId(UUID receivableId) {
        return springDataRepository.findByReceivableId(receivableId).map(SettlementJpaEntity::toDomain);
    }
}
