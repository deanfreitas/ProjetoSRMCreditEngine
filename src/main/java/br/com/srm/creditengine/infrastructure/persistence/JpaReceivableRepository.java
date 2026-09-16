package br.com.srm.creditengine.infrastructure.persistence;

import br.com.srm.creditengine.domain.receivable.ConcurrentSettlementException;
import br.com.srm.creditengine.domain.receivable.Receivable;
import br.com.srm.creditengine.domain.receivable.ReceivableRepository;
import br.com.srm.creditengine.domain.receivable.ReceivableStatus;
import br.com.srm.creditengine.infrastructure.persistence.entity.ReceivableJpaEntity;
import br.com.srm.creditengine.infrastructure.persistence.jpa.SpringDataReceivableRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaReceivableRepository implements ReceivableRepository {

    private final SpringDataReceivableRepository springDataRepository;

    public JpaReceivableRepository(SpringDataReceivableRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public Optional<Receivable> findById(UUID id) {
        return springDataRepository.findById(id).map(ReceivableJpaEntity::toDomain);
    }

    @Override
    public Receivable save(Receivable receivable) {
        ReceivableJpaEntity entity = ReceivableJpaEntity.fromDomain(receivable);
        ReceivableJpaEntity saved = springDataRepository.saveAndFlush(entity);
        return saved.toDomain();
    }

    @Override
    public void settle(Receivable receivable) {
        int affected = springDataRepository.updateStatusWithOptimisticLock(
                receivable.id(),
                receivable.version(),
                ReceivableStatus.PENDING,
                ReceivableStatus.SETTLED
        );
        if (affected == 0) {
            throw new ConcurrentSettlementException(receivable.id(), null);
        }
    }
}
